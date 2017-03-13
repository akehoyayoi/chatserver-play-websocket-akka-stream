package infrastructure.chat

import javax.inject.Inject

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ BroadcastHub, Flow, Keep, MergeHub, Sink }
import akka.stream.{ KillSwitches, Materializer, UniqueKillSwitch }
import domains.chat.{ ChatMessage, ChatRoom, ChatRoomRepository }

import scala.concurrent.duration._

/**
 * Chat room client.
 */
class ChatRoomClient @Inject()(
    implicit val materializer: Materializer,
    implicit val system: ActorSystem
) extends ChatRoomRepository {

  import ChatRoomClient._

  override def chatRoom(roomId: String): ChatRoom = synchronized {
    roomPool.get(roomId) match {
      case Some(room) =>
        room
      case None =>
        val room = create(roomId)
        roomPool += (roomId -> room)
        room
    }
  }

  private def create(roomId: String): ChatRoom = {

    // Create bus parts.
    val (sink, source) =
      MergeHub.source[ChatMessage](perProducerBufferSize = 16)
          .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
          .run()

    // Connect "drain outlet".
    source.runWith(Sink.ignore)

    // Connect parts and create bus.
    val bus: Flow[ChatMessage, ChatMessage, UniqueKillSwitch] = Flow.fromSinkAndSource(sink, source)
        .joinMat(KillSwitches.singleBidi[ChatMessage, ChatMessage])(Keep.right)
        .backpressureTimeout(3.seconds)

    ChatRoom(roomId, bus)
  }

}

object ChatRoomClient {

  var roomPool: scala.collection.mutable.Map[String, ChatRoom] = scala.collection.mutable.Map()

}
