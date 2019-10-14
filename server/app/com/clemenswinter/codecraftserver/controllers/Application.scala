package com.clemenswinter.codecraftserver.controllers

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

import javax.inject._
import betterviews._
import cwinter.codecraft.core.multiplayer.{DetailedStatus, Server}

import scala.concurrent.duration._
import scala.language.postfixOps
import akka.pattern.ask
import akka.util.Timeout
import play.api.mvc._

import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import upickle.default._

@Singleton
class Application @Inject()(
  cc: ControllerComponents,
  val multiplayerServer: MultiplayerServer
) extends AbstractController(cc) {

  def index = Action {
    Ok(Index()).as("text/html")
  }

  def observe = Action {
    Ok(Observe()).as("text/html")
  }

  def startGame(maxTicks: Option[Int], actionDelay: Int, scriptedOpponent: Boolean) = Action {
    implicit request =>
      val body = request.body.asJson.get.toString
      val customMap = if (body == "\"\"") None else Some(read[MapSettings](body))
      val id = multiplayerServer.startGame(maxTicks, scriptedOpponent, customMap)
      Ok(f"""{"id": $id}""").as("application/json")
  }

  def act(gameID: Int, playerID: Int) = Action { implicit request =>
    val action = read[Action](request.body.asJson.get.toString)
    multiplayerServer.act(gameID, playerID, action)
    Ok("success").as("application/json")
  }

  def playerState(gameID: Int, playerID: Int) = Action {
    val payload = multiplayerServer.observe(gameID, playerID)
    Ok(write(payload)).as("application/json")
  }

  def batchAct() = Action { implicit request =>
    val actions = read[Map[String, Action]](request.body.asJson.get.toString)
    for ((gameID, action) <- actions) {
      val (gid, pid) = if (gameID.contains('.')) {
        val parts = gameID.split('.')
        (parts(0).toInt, parts(1).toInt)
      } else {
        (gameID.toInt, 0)
      }
      multiplayerServer.act(gid, pid, action)
    }
    Ok("success").as("application/json")
  }

  def batchPlayerState(json: Boolean) = Action { implicit request =>
    val games = read[Seq[(Int, Int)]](request.body.asJson.get.toString)
    val payload: Seq[Observation] = for ((gameID, playerID) <- games)
      yield multiplayerServer.observe(gameID, playerID)
    if (json) {
      Ok(write(payload)).as("application/json")
    } else {
      Ok(serializeObs(payload)).as("application/octet-stream")
    }
  }

  def serializeObs(obs: Seq[Observation]): Array[Byte] = {
    val droneProperties = 13
    val nums = obs.length * (1 + droneProperties + 4 * 10 + droneProperties * 10 + 3)
    val bb: ByteBuffer = ByteBuffer.allocate(4 * nums)
    bb.order(ByteOrder.nativeOrder)
    for (ob <- obs) {
      // Time
      bb.putFloat(ob.timestep.toFloat / ob.maxGameLength)

      // Allied drone
      var x = 0.0f
      var y = 0.0f
      ob.alliedDrones.headOption match {
        case Some(drone) =>
          x = drone.xPos
          y = drone.yPos
          bb.putFloat(x / 1000.0f)
          bb.putFloat(y / 1000.0f)
          bb.putFloat(math.sin(drone.orientation).toFloat)
          bb.putFloat(math.cos(drone.orientation).toFloat)
          bb.putFloat(drone.storedResources / 50.0f)
          bb.putFloat(if (drone.isConstructing) 1.0f else -1.0f)
          bb.putFloat(if (drone.isHarvesting) 1.0f else -1.0f)
          bb.putFloat(0.1f * drone.hitpoints)
          bb.putFloat(0.5f * drone.storageModules)
          bb.putFloat(0.5f * drone.missileBatteries)
          bb.putFloat(0.5f * drone.constructors)
          bb.putFloat(0.5f * drone.engines)
          bb.putFloat(0.5f * drone.shieldGenerators)
        case None => for (_ <- 0 until droneProperties) bb.putFloat(0.0f)
      }

      // 10 closest minerals
      for (m <- ob.minerals.sortBy(m => (m.xPos * m.xPos + m.yPos * m.yPos) / m.size).take(10)) {
        bb.putFloat((m.xPos - x) / 1000.0f)
        bb.putFloat((m.yPos - y) / 1000.0f)
        bb.putFloat(math.sqrt((m.yPos - y) * (m.yPos - y) + (m.xPos - x) * (m.xPos - x)).toFloat / 1000.0f)
        bb.putFloat(m.size / 100.0f)
      }
      for (_ <- 0 until (10 - ob.minerals.size) * 4) {
        bb.putFloat(0.0f)
      }

      // 10 closest enemy drones
      for (drone <- ob.enemyDrones.sortBy(m => (m.xPos * m.xPos + m.yPos * m.yPos) / m.size).take(10)) {
        bb.putFloat((drone.xPos - x) / 1000.0f)
        bb.putFloat((drone.yPos - y) / 1000.0f)
        bb.putFloat(math.sin(drone.orientation).toFloat)
        bb.putFloat(math.cos(drone.orientation).toFloat)
        bb.putFloat(drone.storedResources / 50.0f)
        bb.putFloat(if (drone.isConstructing) 1.0f else -1.0f)
        bb.putFloat(if (drone.isHarvesting) 1.0f else -1.0f)
        bb.putFloat(0.1f * drone.hitpoints)
        bb.putFloat(0.5f * drone.storageModules)
        bb.putFloat(0.5f * drone.missileBatteries)
        bb.putFloat(0.5f * drone.constructors)
        bb.putFloat(0.5f * drone.engines)
        bb.putFloat(0.5f * drone.shieldGenerators)
      }
      for (_ <- 0 until (10 - ob.enemyDrones.size) * droneProperties) {
        bb.putFloat(0.0f)
      }
    }
    for (ob <- obs) {
      bb.putFloat(ob.winner.map(_ + 1.0f).getOrElse(0))
      bb.putFloat(ob.alliedScore.toFloat)
      bb.putFloat(ob.enemyScore.toFloat)
    }
    val result = bb.array()
    assert(result.length == 4 * nums, f"Expected $nums elements, actual: ${result.length}")
    result
  }

  def mpssJson = Action.async { implicit request =>
    val maxGameStats = request
      .getQueryString("maxgames")
      .fold(250)(x => Try { x.toInt }.getOrElse(250))
    implicit val timeout = Timeout(1 seconds)
    val serverStatusFuture = multiplayerServer.actorRef ? Server.GetDetailedStatus
    for {
      untypedStatus <- serverStatusFuture
      status = untypedStatus.asInstanceOf[DetailedStatus]
      lessGames = status.games.sortBy(-_.startTimestamp).take(maxGameStats)
    } yield Ok(write(status.copy(games = lessGames))).as("application/json")
  }

  def debugState = Action {
    Ok(write(multiplayerServer.debugState)).as("application/json")
  }
}
