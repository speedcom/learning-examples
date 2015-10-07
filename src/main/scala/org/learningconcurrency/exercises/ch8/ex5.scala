package org.learningconcurrency
package ch8

import akka.actor._
import com.typesafe.config._
import scala.collection._
import akka.event.Logging
import akka.util.Timeout
import akka.pattern.{ask, pipe, gracefulStop}
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util._

object ActorsSystem {
  lazy val system = ActorSystem("failure-detector-system")
}

class FailureDetector(parentActor: ActorRef, actors: Seq[ActorRef]) extends Actor {
  import ActorsSystem.system.dispatcher

  val log = Logging(context.system, this)

  val interval  = 6 seconds
  val threshold = 2 seconds
  implicit val timeout = Timeout(threshold)

  ActorsSystem.system.scheduler.schedule(0 seconds, interval) {
    for(actorRef <- actors) {
      actorRef ! Identify(actorRef.path)
    }
  }

  def receive = {
    case ActorIdentity(path, Some(ref)) =>
      log.info(s"found actor $ref at $path")
    case ActorIdentity(path, None) =>
      log.info(s"could not find an actor at $path")
      parentActor ! Parent.Failed(path)
  }

  override def preStart() = { log.info(s"failure detector has started to monitor these actors $actors") }
}
object FailureDetector {
  def props(parentActor: ActorRef, actors: Seq[ActorRef]) = Props(new FailureDetector(parentActor, actors))
}

class Parent extends Actor {
  val log = Logging(context.system, this)

  def receive = {
    case Parent.Failed(child)  => log.info(s"informed that my child with path $child does not work!")
    case Parent.CreateChild    => context.actorOf(Children.props)
    case Parent.ListChildren   => log.info(context.children.toString)
    case Parent.ReturnChildren => sender ! context.children
  }
}
object Parent {
  def props = Props[Parent]

  case class Failed(path: Any)
  case object CreateChild
  case object ListChildren
  case object ReturnChildren
}

class Children extends Actor {
  val log = Logging(context.system, this)

  def receive = {
    case "stop" => context.stop(self)
  }
}
object Children {
  def props = Props[Children]
}

object ex5 extends App {
  import scala.concurrent.Await
  import scala.collection._

  val system = ActorsSystem.system

  val parentRef = system.actorOf(Parent.props, "parent-actors")

  parentRef ! Parent.CreateChild
  parentRef ! Parent.CreateChild
  parentRef ! Parent.CreateChild
  parentRef ! Parent.CreateChild

  Thread.sleep(1000)

  parentRef ! Parent.ListChildren // should print out 4

  implicit val timeout = Timeout(5 seconds)

  val futureChildren = parentRef ? Parent.ReturnChildren
  val children = Await.result(futureChildren, timeout.duration).asInstanceOf[immutable.Iterable[ActorRef]]

  val monitoredChildren = children.take(2).toSeq

  system.actorOf(FailureDetector.props(parentRef, monitoredChildren), "failure-detector")

  parentRef ! Parent.ListChildren // should print out 4

  Thread.sleep(12000)

  println("Trying to stop actor " + monitoredChildren(0))
  println("Trying to stop actor " + monitoredChildren(1))
  monitoredChildren(0) ! "stop"
  monitoredChildren(1) ! "stop"

  Thread.sleep(5000)

  parentRef ! Parent.ListChildren // should print out 2

  system.shutdown()
}