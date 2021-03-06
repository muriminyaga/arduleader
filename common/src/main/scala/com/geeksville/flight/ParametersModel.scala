package com.geeksville.flight

import com.geeksville.mavlink.HeartbeatMonitor
import org.mavlink.messages.ardupilotmega._
import org.mavlink.messages.MAVLinkMessage
import com.geeksville.akka.MockAkka
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.mutable.ArrayBuffer
import com.geeksville.util.Throttled
import com.geeksville.akka.EventStream
import org.mavlink.messages.MAV_TYPE
import com.geeksville.akka.Cancellable
import org.mavlink.messages.MAV_DATA_STREAM
import org.mavlink.messages.MAV_MISSION_RESULT
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashSet
import com.geeksville.mavlink.MavlinkEventBus
import com.geeksville.mavlink.MavlinkStream
import com.geeksville.util.ThrottledActor

//
// Messages we publish on our event bus when something happens
//
case object MsgParametersDownloaded

// We just received a new parameter (after the initial download)
case class MsgParameterReceived(index: Int)

/**
 * Listens to a particular vehicle, capturing interesting state like heartbeat, cur lat, lng, alt, mode, status and next waypoint
 */
trait ParametersModel extends VehicleClient with ParametersReadOnlyModel {

  case object FinishParameters

  var parameters = new Array[ParamValue](0)
  var parametersById = Map[String, ParamValue]()

  private var retryingParameters = false
  var unsortedParameters = new Array[ParamValue](0)

  var finisher: Option[Cancellable] = None

  /**
   * This feature is not supported on older arducopter builds
   */
  private val useRequestById = false

  private var numAttemptsRemaining = 10

  /**
   * Wrap the raw message with clean accessors, when a value is set, apply the change to the target
   */
  class ParamValue extends ROParamValue {
    def setValueNoAck(v: Float) = {
      val p = raw.getOrElse(throw new Exception("Can not set uninited param"))

      p.param_value = v
      val msg = paramSet(p.getParam_id, p.param_type, v)
      log.debug("Setting param: " + msg + " from=" + p)
      sendMavlink(msg)
      p
    }

    def setValue(v: Float) {
      val p = setValueNoAck(v)

      // Readback to confirm the change happened
      reread()
    }

    def reread() {
      val p = raw.getOrElse(throw new Exception("Can not reread uninited param"))
      requestParameterById(p.getParam_id)
    }
  }

  protected def onParametersDownloaded() {
    setStreamEnable(true) // Turn streaming back on

    log.info("Downloaded " + unsortedParameters.size + " parameters!")
    parameters = unsortedParameters.sortWith { case (a, b) => a.getId.getOrElse("ZZZ") < b.getId.getOrElse("ZZZ") }

    // We only index params with valid ids
    val known = parameters.flatMap { p =>
      p.getId.map { id => id -> p }
    }
    parametersById = Map(known: _*)

    eventStream.publish(MsgParametersDownloaded)
  }

  override def onReceive = mReceive.orElse(super.onReceive)

  private def mReceive: Receiver = {

    //
    // Messages for downloading parameters from vehicle

    case msg: msg_param_value =>
      // log.debug("Receive: " + msg)
      checkRetryReply(msg)
      if (msg.param_count != unsortedParameters.size) {
        // Resize for new parameter count
        unsortedParameters = ArrayBuffer.fill(msg.param_count)(new ParamValue).toArray
      }

      log.debug("Received param: " + msg)

      var index = msg.param_index
      if (index == 65535) { // Apparently means unknown index, so look up by id
        val idstr = msg.getParam_id
        index = unsortedParameters.find { p =>
          p.getId.getOrElse("") == idstr
        }.get.raw.get.param_index

        // We now know where this param belongs
        msg.param_index = index
      }

      unsortedParameters(index).raw = Some(msg)

      // If during our initial download we can use the param index as the index, but later we are sorted and have to do something smarter
      if (retryingParameters) {
        readNextParameter()
      } else {
        // Are we done with our initial download early?  If so, we can publish done right now
        if (finisher.isDefined && msg.param_index == msg.param_count - 1) {
          finisher.foreach(_.cancel())
          finisher = None
          self ! FinishParameters
        }

        // After we have a sorted param list, we will start publishing updates for individual parameters
        val paramNum = parameters.indexWhere(_.raw.map(_.param_index).getOrElse(-1) == index)

        if (paramNum != -1) {
          log.debug("publishing param " + paramNum)
          eventStream.publish(MsgParameterReceived(paramNum))
        }
      }

    case FinishParameters =>
      if (useRequestById)
        readNextParameter()
      else
        perhapsDownloadParameters()
  }

  protected def startParameterDownload() {
    // We could be called multiple times, because we are called after every waypoint download.  If we already have parameters,
    // don't start over
    if (parametersById.isEmpty) {
      retryingParameters = false

      // Turn off streaming because those crummy XBee adapters seem to drop critical parameter responses
      setStreamEnable(false)

      restartParameterDownload()
    }
  }

  /**
   * Ask for another batch of parameters
   */
  private def restartParameterDownload() {
    log.info("Requesting vehicle parameters")
    sendWithRetry(paramRequestList(), classOf[msg_param_value])
    finisher.foreach(_.cancel)
    finisher = Some(MockAkka.scheduler.scheduleOnce(30 seconds, ParametersModel.this, FinishParameters))
  }

  private def requestParameterByIndex(i: Int) {
    sendWithRetry(paramRequestReadByIndex(i), classOf[msg_param_value], { () =>
      // We failed, just tell everyone we are done
      onParametersDownloaded()
    })
  }

  private def requestParameterById(id: String) {
    sendWithRetry(paramRequestReadById(id), classOf[msg_param_value])
  }

  private def numMissingParameters() =
    unsortedParameters.count(!_.raw.isDefined)

  /**
   * Check to see if we are missing any params, if we are, try to download again
   */
  private def perhapsDownloadParameters() {
    val numMissing = numMissingParameters
    log.info("Number of missing parameters: " + numMissing)
    if (numMissing > 0 && numAttemptsRemaining > 0) {
      numAttemptsRemaining -= 1
      log.warn("Asking for params, attempts remaining: " + numAttemptsRemaining)
      restartParameterDownload()
    } else
      onParametersDownloaded() // Yay!  Success (or we gave up)
  }

  /**
   * If we are still missing parameters, try to read again
   */
  private def readNextParameter() {
    val isMissing = unsortedParameters.zipWithIndex.find {
      case (v, i) =>
        val hasData = v.raw.isDefined
        if (!hasData)
          requestParameterByIndex(i)

        !hasData // Stop here?
    }.isDefined

    retryingParameters = isMissing
    if (!isMissing) {
      onParametersDownloaded() // Yay - we have everything!
    }
  }
}

