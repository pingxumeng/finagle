package com.twitter.finagle.serverset2

import collection.immutable
import com.twitter.conversions.time._
import com.twitter.finagle.MockTimer
import com.twitter.finagle.serverset2.client._
import com.twitter.io.Buf
import com.twitter.util._
import java.util.concurrent.atomic.AtomicReference
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

sealed private trait ZkOp { type Res; val res = new Promise[Res] }
private object ZkOp {
  case class Exists(path: String) extends ZkOp {
    type Res = Option[Data.Stat]
  }

  case class ExistsWatch(path: String) extends ZkOp {
     type Res = Watched[Option[Data.Stat]]
  }

  case class GetChildren(path: String) extends ZkOp {
     type Res = Node.Children
  }

  case class GetChildrenWatch(path: String) extends ZkOp {
     type Res = Watched[Node.Children]
  }

  case class GetData(path: String) extends ZkOp {
     type Res = Node.Data
  }

  case class GetDataWatch(path: String) extends ZkOp {
     type Res = Watched[Node.Data]
  }

  case class Sync(path: String) extends ZkOp {
     type Res = Unit
  }

  case class Close(deadline: Time) extends ZkOp {
    type Res = Unit
  }
}

private class OpqueueZkReader(
    val sessionId: Long,
    val sessionPasswd: Buf,
    val sessionTimeout: Duration) extends ZooKeeperReader {

  import ZkOp._

  def this() = this(0, Buf.Empty, Duration.Zero)

  @volatile var opq: immutable.Queue[ZkOp] = immutable.Queue.empty

  private def enqueue(op: ZkOp): Future[op.Res] = synchronized {
    opq = opq enqueue op
    op.res
  }

  def exists(path: String) = enqueue(Exists(path))
  def existsWatch(path: String) = enqueue(ExistsWatch(path))

  def getChildren(path: String) = enqueue(GetChildren(path))
  def getChildrenWatch(path: String) = enqueue(GetChildrenWatch(path))

  def getData(path: String) = enqueue(GetData(path))
  def getDataWatch(path: String) = enqueue(GetDataWatch(path))

  def sync(path: String) = enqueue(Sync(path))
  def close(deadline: Time) = enqueue(Close(deadline))

  def addAuthInfo(scheme: String, auth: Buf): Future[Unit] = Future.never

  def getACL(path: String): Future[Node.ACL] = Future.never
}

@RunWith(classOf[JUnitRunner])
class ZkTest extends FunSuite {

  import ZkOp._

  test("ops retry safely") { Time.withCurrentTimeFrozen { tc =>
    val timer = new MockTimer
    val watchedZk = Watched(new OpqueueZkReader(), Var(WatchState.Pending))
    val zk = new Zk(watchedZk, timer)

    val v = zk.existsOf("/foo/bar")
    // An unobserved Var makes no side effect.
    assert(watchedZk.value.opq.isEmpty)
    val ref = new AtomicReference[Activity.State[Option[Data.Stat]]]
    val o = v.states.register(Witness(ref))
    assert(watchedZk.value.opq === Seq(ExistsWatch("/foo/bar")))
    assert(ref.get === Activity.Pending)
    
    assert(timer.tasks.isEmpty)
    watchedZk.value.opq(0).res() = Throw(new KeeperException.ConnectionLoss(None))
    assert(timer.tasks.size === 1)
    tc.advance(10.milliseconds)
    timer.tick()
    assert(watchedZk.value.opq === Seq(ExistsWatch("/foo/bar"), ExistsWatch("/foo/bar")))
    assert(ref.get === Activity.Pending)
    
    watchedZk.value.opq(1).res() = Throw(new KeeperException.SessionExpired(None))
    assert(watchedZk.value.opq === Seq(ExistsWatch("/foo/bar"), ExistsWatch("/foo/bar")))
    val Activity.Failed(exc) = ref.get
    assert(exc.isInstanceOf[KeeperException.SessionExpired])
  }}
  
  test("Zk.childrenOf") { Time.withCurrentTimeFrozen { tc =>
    val timer = new MockTimer
    val watchedZk = Watched(new OpqueueZkReader(), Var(WatchState.Pending))
    val zk = new Zk(watchedZk, timer)
    
    val v = zk.childrenOf("/foo/bar")
    val ref = new AtomicReference[Activity.State[Set[String]]]
    v.states.register(Witness(ref))
    assert(ref.get === Activity.Pending)
    
    val Seq(ew@ExistsWatch("/foo/bar")) = watchedZk.value.opq
    val ewwatchv = Var[WatchState](WatchState.Pending)
    ew.res() = Return(Watched(None, ewwatchv))
    assert(watchedZk.value.opq === Seq(ExistsWatch("/foo/bar")))
    assert(ref.get === Activity.Ok(Set.empty))

    ewwatchv() = WatchState.Determined(NodeEvent.ChildrenChanged)
    val Seq(`ew`, ew2@ExistsWatch("/foo/bar")) = watchedZk.value.opq
    assert(ref.get === Activity.Ok(Set.empty))
    val ew2watchv = Var[WatchState](WatchState.Pending)
    ew2.res() = Return(Watched(Some(Data.Stat(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), ew2watchv))
    val Seq(`ew`, `ew2`, gcw@GetChildrenWatch("/foo/bar")) = watchedZk.value.opq
    assert(ref.get === Activity.Pending)
    gcw.res() = Return(Watched(Node.Children(Seq("a", "b", "c"), Data.Stat(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), Var.value(WatchState.Pending)))
    assert(ref.get === Activity.Ok(Set("a", "b", "c")))
    assert(watchedZk.value.opq === Seq(ew, ew2, gcw))

    ew2watchv() = WatchState.Determined(NodeEvent.ChildrenChanged)
    val Seq(`ew`, `ew2`, `gcw`, ew3@ExistsWatch("/foo/bar")) = watchedZk.value.opq
    ew3.res() = Return(Watched(None, Var.value(WatchState.Pending)))
    assert(ref.get === Activity.Ok(Set.empty))
  }}
}
