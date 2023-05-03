package shopping.cart.repository

import scalikejdbc.{DBSession, scalikejdbcSQLInterpolationImplicitDef}

class ItemPopularityRepositoryImpl extends ItemPopularityRepository {
  override def update(
    session: ScalikeJdbcSession,
    itemId: String,
    delta: Int
  ): Unit = {
    session.db.withinTx { implicit dbSession =>
      sql"""
        INSERT INTO item_popularity (item_id, count)
        VALUES ($itemId, $delta)
        ON CONFLICT (item_id)
        DO UPDATE SET count = item_popularity.count + $delta
        """.executeUpdate().apply()
    }
  }

  override def getItem(
    session: ScalikeJdbcSession,
    itemId: String
  ): Option[Long] = {
    if (session.db.isTxAlreadyStarted) {
      session.db.withinTx { implicit dbSession =>
        select(itemId )
      }
    } else {
      session.db.readOnly { implicit dbSession =>
        select(itemId)
      }
    }
  }

  private def select(str: String)(implicit dbSession: DBSession) = {
    sql"""
      SELECT count FROM item_popularity WHERE item_id = $str
      """
      .map(_.long("count"))
      .toOption()
      .apply()
  }
}
