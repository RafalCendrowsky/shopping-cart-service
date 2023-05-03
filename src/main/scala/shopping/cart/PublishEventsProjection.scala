package shopping.cart

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.SendProducer
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.{ProjectionBehavior, ProjectionId}
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.AtLeastOnceProjection
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}
import shopping.cart.ShoppingCart.Event
import shopping.cart.repository.ScalikeJdbcSession

object PublishEventsProjection {

  def init(system: ActorSystem[_]): Unit = {
    val sendProducer = createProducer(system)
    val topic = system.settings.config.getString("shopping-cart-service.kafka.topic")
    ShardedDaemonProcess(system).init(
      name = "PublishEventsProjection",
      ShoppingCart.tags.size,
      index => ProjectionBehavior(createProjectionFor(system, topic, sendProducer, index)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop)
    )
  }

  private def createProducer(system: ActorSystem[_]): SendProducer[String, Array[Byte]] = {
    val producerSettings = ProducerSettings(system, new StringSerializer, new ByteArraySerializer)
    val sendProducer = SendProducer(producerSettings)(system)
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseBeforeServiceUnbind,
      "close-send-producer"
    ) { () => sendProducer.close()
    }
    sendProducer
  }

  private def createProjectionFor(
    system: ActorSystem[_],
    topic: String,
    producer: SendProducer[String, Array[Byte]],
    index: Int) : AtLeastOnceProjection[Offset, EventEnvelope[Event]] = {
    val tag = ShoppingCart.tags(index)
    val sourceProvider = EventSourcedProvider.eventsByTag[Event](
      system = system,
      readJournalPluginId = JdbcReadJournal.Identifier,
      tag = tag
    )
    JdbcProjection.atLeastOnceAsync(
      projectionId = ProjectionId("PublishEventsProjection", tag),
      sourceProvider,
      handler = () => new PublishEventsProjectionHandler(system, topic, producer),
      sessionFactory = () => new ScalikeJdbcSession()
    )(system)
  }

}
