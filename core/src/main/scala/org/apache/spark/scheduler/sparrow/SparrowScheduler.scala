/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler.sparrow

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap

import edu.berkeley.sparrow.api.SparrowFrontendClient
import edu.berkeley.sparrow.thrift.{FrontendService, TFullTaskId, TPlacementPreference, TTaskSpec,
TUserGroupInfo}

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler._
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.util.AccumulatorV2

private[spark] class SparrowScheduler(val sc: SparkContext,
    host: String, port: String, frameworkName: String)
  extends TaskScheduler with FrontendService.Iface with Logging {
  private var backend: SchedulerBackend = null

  val conf = sc.conf
  private val client = new SparrowFrontendClient
  private val user = new TUserGroupInfo("sparkUser", "group", 0)
  private val taskId = new AtomicInteger

  // Mapping of task ids to Tasks because we need to pass a Task back to the listener on task end.
  private val tidToTask = new HashMap[String, Task[_]]()

  private val ser = SparkEnv.get.closureSerializer.newInstance()

  // The pool is just used for the UI -- so fine if we don't set it.
  val schedulingMode: SchedulingMode = SchedulingMode.withName("FIFO")
  var rootPool = new Pool("", schedulingMode, 0, 0)

  override def submitTasks(taskSet: TaskSet): Unit = synchronized {
    val tasks = taskSet.tasks
    logInfo("Adding task set " + taskSet.id + " with " + tasks.length + " tasks")
    val sparrowTasks = ((1 to tasks.length ) zip tasks).map(t => {
      val spec = new TTaskSpec
      spec.setPreference{
        val placement = new TPlacementPreference
        t._2.preferredLocations.foreach(p => placement.addToNodes(p.host))
        placement
      }
      val serializedTask: ByteBuffer = Task.serializeWithDependencies(t._2, sc.addedFiles,
        sc.addedJars, ser)
      val taskName = s"task $taskId in stage ${taskSet.id}"
      val serializedTaskDesc: ByteBuffer = ser.serialize(
        new TaskDescription(taskId = taskId.longValue(),
        attemptNumber = 1, "dummy_id_exec",
        taskName, t._1, serializedTask))
      spec.setMessage(serializedTaskDesc)
      val tid = taskId.incrementAndGet().toString()
      tidToTask(tid) = t._2
      spec.setTaskId(tid)
      spec
    })

    val description = {
      if (taskSet.properties == null) {
        ""
      } else {
        val sparkJobDescription = taskSet.properties.getProperty(
          SparkContext.SPARK_JOB_DESCRIPTION, "").replace("\n", " ")
        "%s-%s".format(sparkJobDescription, taskSet.stageId)
      }
    }

    new Thread(new Runnable() {
      override def run() {
        client.submitJob(frameworkName, sparrowTasks.toList.asJava, user, description)
        logInfo("Submitted taskSet with id=%s time=%s".format(taskSet.id, System.currentTimeMillis))
      }
    }).start()
  }

  def initialize(backend: SchedulerBackend) {
    this.backend = backend;
  }

  override def start() {
    backend.start();
    val socketAddress = new InetSocketAddress(host, port.toInt)
    client.initialize(socketAddress, frameworkName, this)
  }

  override def stop() {
    backend.stop();
  }

  override def defaultParallelism(): Int =
    System.getProperty("spark.default.parallelism", "8").toInt

  // Listener object to pass upcalls into
  var dagScheduler: DAGScheduler = null

  override def setDAGScheduler(dagScheduler: DAGScheduler) {
    this.dagScheduler = dagScheduler
  }

  // TODO: have taskStarted message from Sparrow; on receiving that call taskStarted
  // on task listener.

  override def frontendMessage(taskId: TFullTaskId, statusInt: Int, message: ByteBuffer): Unit =
    synchronized {
    logInfo(s"got frontend msg taskId $taskId, $statusInt")
    TaskState.apply(statusInt) match {
      case TaskState.FINISHED =>
        val result = ser.deserialize[DirectTaskResult[_]](message, getClass.getClassLoader)
        // FIXME: get this information from Sparrow, rather than fudging it.
        val taskInfo = new TaskInfo(
          taskId.getTaskId.toLong,
          0,
          0,
          System.currentTimeMillis,
          "dummyexecId",
          "foo:bar",
          TaskLocality.PROCESS_LOCAL, conf.getBoolean("spark.speculation", false))
        taskInfo.finishTime = System.currentTimeMillis
        taskInfo.failed = false
        dagScheduler.taskEnded(
          tidToTask(taskId.getTaskId()),
          Success,
          result.value,
          result.accumUpdates,
          taskInfo)

      case status =>
        // TODO: the fact that we don't support task started right now causes UI problems.
        logWarning("Got unexpected task state: " + status)
    }
  }

  // Cancel a stage.
  override def cancelTasks(stageId: Int, interruptThread: Boolean): Unit = {
    // We don't support this atm.
  }

  override def applicationAttemptId(): Option[String] = {
    Some("fake-attempt-ID") // FIXME: get real ID here.
  }

  override def executorLost(executorId: String, reason: ExecutorLossReason): Unit = {
    // noop. //fixme
  }

  /*
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  override def executorHeartbeatReceived(
    execId: String,
    accumUpdates: Array[(Long, Seq[AccumulatorV2[_, _]])],
    blockManagerId: BlockManagerId): Boolean = {
    val accumUpdatesWithTaskIds: Array[(Long, Int, Int, Seq[AccumulableInfo])] = synchronized {
      accumUpdates.flatMap { case (id, updates) =>
        val accInfos = updates.map(acc => acc.toInfo(Some(acc.value), None))
        tidToTask.get(id.toString).map { task =>
          (id, task.stageId, task.stageAttemptId, accInfos)
        }
      }
    }
    dagScheduler.executorHeartbeatReceived(execId, accumUpdatesWithTaskIds, blockManagerId)
  }

}
