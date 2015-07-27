package controllers

import com.bryzek.apidoc.api.v0.models.Membership
import com.bryzek.apidoc.api.v0.models.json._
import db.{Authorization, MembershipsDao}
import play.api.mvc._
import play.api.libs.json.Json
import java.util.UUID

class Memberships extends Controller {

  def get(
    organizationGuid: Option[UUID],
    organizationKey: Option[String],
    userGuid: Option[UUID],
    role: Option[String],
    limit: Long = 25,
    offset: Long = 0
  ) = Authenticated { request =>
    Ok(
      Json.toJson(
        MembershipsDao.findAll(
          request.authorization,
          organizationGuid = organizationGuid,
          organizationKey = organizationKey,
          userGuid = userGuid,
          role = role,
          limit = limit,
          offset = offset
        )
      )
    )
  }

  def getByGuid(guid: UUID) = Authenticated { request =>
    MembershipsDao.findByGuid(request.authorization, guid) match {
      case None => NotFound
      case Some(membership) => {
        if (MembershipsDao.isUserAdmin(request.user, membership.organization)) {
          Ok(Json.toJson(membership))
        } else {
          Unauthorized
        }
      }
    }
  }

  def deleteByGuid(guid: UUID) = Authenticated { request =>
    MembershipsDao.findByGuid(request.authorization, guid) match {
      case None => NoContent
      case Some(membership) => {
        if (MembershipsDao.isUserAdmin(request.user, membership.organization)) {
          MembershipsDao.softDelete(request.user, membership)
          NoContent
        } else {
          Unauthorized
        }
      }
    }
  }

}
