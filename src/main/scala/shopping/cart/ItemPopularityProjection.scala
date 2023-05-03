package shopping.cart

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.{ProjectionBehavior, ProjectionId}
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import shopping.cart.ShoppingCart.Event
import shopping.cart.repository.{ItemPopularityProjectionHandler, ItemPopularityRepository, ScalikeJdbcSession}

object ItemPopularityProjection {

  def init(
    system: ActorSystem[_],
    repository: ItemPopularityRepository
  ): Unit = {
    ShardedDaemonProcess(system).init(
      name = "ItemPopularityProjection",
      ShoppingCart.tags.size,
      index => ProjectionBehavior(createProjectionFor(system, repository, index)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop)
    )
  }

  private def createProjectionFor(
      system: ActorSystem[_],
      repository: ItemPopularityRepository,
      index: Int
  ): ExactlyOnceProjection[Offset, EventEnvelope[Event]]= {
    val tag = ShoppingCart.tags(index)
    val sourceProvider: SourceProvider[Offset, EventEnvelope[Event]] =
      EventSourcedProvider.eventsByTag[Event](
        system = system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = tag
      )
    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId("ItemPopularityProjection", tag),
      sourceProvider,
      handler = () => new ItemPopularityProjectionHandler(tag, system, repository),
      sessionFactory = () => new ScalikeJdbcSession()
    )(system)
  }

}
