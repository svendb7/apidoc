package controllers

import models.MainTemplate
import lib.{UrlKey, Util, VersionedName, VersionTag}
import com.gilt.apidoc.v0.models.{Application, OriginalForm, OriginalType, Organization, User, Version, VersionForm, Visibility, WatchForm}
import com.gilt.apidoc.spec.v0.models.Service
import com.gilt.apidoc.spec.v0.models.json._
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.data._
import play.api.data.Forms._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import java.io.File

object Versions extends Controller {

  private val DefaultVersion = "0.0.1-dev"
  private val LatestVersion = "latest"

  implicit val context = scala.concurrent.ExecutionContext.Implicits.global

  def redirectToLatest(orgKey: String, applicationKey: String) = Action {
    Redirect(routes.Versions.show(orgKey, applicationKey, LatestVersion))
  }

  def show(orgKey: String, applicationKey: String, versionName: String) = AnonymousOrg.async { implicit request =>
    for {
      applicationResponse <- request.api.applications.getByOrgKey(orgKey = orgKey, key = Some(applicationKey))
      versionsResponse <- request.api.versions.getByOrgKeyAndApplicationKey(orgKey, applicationKey)
      versionOption <- request.api.versions.getByOrgKeyAndApplicationKeyAndVersion(orgKey, applicationKey, versionName)
      generators <- request.api.Generators.get()
      watches <- isWatching(request.api, request.user, orgKey, applicationKey)
    } yield {
      applicationResponse.headOption match {
        case None => {
          Redirect(routes.Organizations.show(orgKey)).flashing("warning" -> s"Application not found: ${applicationKey}")
        }
        case Some(application) => {
          versionOption match {

            case None => {
              if (LatestVersion == versionName) {
                Redirect(routes.Versions.create(orgKey, application = Some(applicationKey))).flashing("success" -> s"Application does not yet have any versions")
              } else {
                Redirect(routes.Versions.show(orgKey, applicationKey, LatestVersion)).flashing("warning" -> s"Version not found: $versionName")
              }
            }

            case Some(v: Version) => {
              // TODO: For updates, include application in the template
              val tpl = request.mainTemplate(Some(v.service.name + " " + v.version)).copy(
                version = Some(v.version),
                allServiceVersions = versionsResponse.map(_.version),
                service = Some(v.service),
                versionObject = Some(v),
                generators = generators.filter(_.enabled)
              )
              Ok(views.html.versions.show(tpl, application, v.service, watches))
            }
          }
        }
      }
    }
  }

  def original(orgKey: String, applicationKey: String, versionName: String) = AnonymousOrg.async { implicit request =>
    request.api.versions.getByOrgKeyAndApplicationKeyAndVersion(orgKey, applicationKey, versionName).map {
      case None => {
        if (LatestVersion == versionName) {
          Redirect(routes.Organizations.show(orgKey)).flashing("warning" -> s"Application not found: ${applicationKey}")
        } else {
          Redirect(routes.Versions.show(orgKey, applicationKey, LatestVersion))
            .flashing("warning" -> s"Version not found: ${versionName}")
        }
      }
      case Some(version) => {
        version.original match {
          case None => {
            Redirect(routes.Versions.show(orgKey, applicationKey, versionName))
              .flashing("warning" -> s"Original not available")
          }
          case Some(original) => {
            original.`type` match {
              case OriginalType.ApiJson => {
                Ok(original.data).withHeaders("Content-Type" -> "application/json")
              }
              case OriginalType.AvroIdl => {
                Ok(original.data).withHeaders("Content-Type" -> "text/plain")
              }
              case OriginalType.UNDEFINED(_) => {
                Ok(original.data).withHeaders("Content-Type" -> "text/plain")
              }
            }
          }
        }
      }
    }
  }

  def serviceJson(orgKey: String, applicationKey: String, versionName: String) = AnonymousOrg.async { implicit request =>
    request.api.versions.getByOrgKeyAndApplicationKeyAndVersion(orgKey, applicationKey, versionName).map {
      case None => {
        if (LatestVersion == versionName) {
          Redirect(routes.Organizations.show(orgKey)).flashing("warning" -> s"Application not found: ${applicationKey}")
        } else {
          Redirect(routes.Versions.show(orgKey, applicationKey, LatestVersion))
            .flashing("warning" -> s"Version not found: ${versionName}")
        }
      }
      case Some(version) => {
        Ok(Json.toJson(version.service)).withHeaders("Content-Type" -> "application/json")
      }
    }
  }

  def postDelete(orgKey: String, applicationKey: String, versionName: String) = AnonymousOrg.async { implicit request =>
    for {
      result <- request.api.versions.deleteByOrgKeyAndApplicationKeyAndVersion(orgKey, applicationKey, versionName)
    } yield {
      result match {
        case None => Redirect(routes.Versions.show(orgKey, applicationKey, versionName)).flashing("success" -> s"Version $versionName was not found or could not be deleted")
        case Some(_) => Redirect(routes.Versions.show(orgKey, applicationKey, LatestVersion)).flashing("success" -> s"Version $versionName deleted")
      }
    }
  }


  def postWatch(orgKey: String, applicationKey: String, versionName: String) = AuthenticatedOrg.async { implicit request =>
    request.api.versions.getByOrgKeyAndApplicationKeyAndVersion(request.org.key, applicationKey, versionName).flatMap {
      case None => {
        if (LatestVersion == versionName) {
          Future {
            Redirect(routes.Organizations.show(orgKey)).flashing("warning" -> s"Application not found: ${applicationKey}")
          }
        } else {
          Future {
            Redirect(routes.Versions.show(orgKey, applicationKey, LatestVersion))
              .flashing("warning" -> s"Version not found: ${versionName}")
          }
        }
      }
      case Some(version) => {
        Await.result(request.api.watches.get(
          userGuid = Some(request.user.guid),
          organizationKey = Some(orgKey),
          applicationKey = Some(applicationKey)
        ), 5000.millis).headOption match {
          case None => {
            request.api.watches.post(
              WatchForm(
                userGuid = request.user.guid,
                organizationKey = orgKey,
                applicationKey = applicationKey
              )
            ).map { _ =>
              Redirect(routes.Versions.show(orgKey, applicationKey, versionName)).flashing("success" -> "You are now watching this application")
            }
          }
          case Some(watch) => {
            request.api.watches.deleteByGuid(watch.guid).map { _ =>
              Redirect(routes.Versions.show(orgKey, applicationKey, versionName)).flashing("success" -> "You are no longer watching this application")
            }
          }
        }
      }
    }
  }

  def create(
    orgKey: String,
    applicationKey: Option[String] = None
  ) = AuthenticatedOrg.async { implicit request =>
    request.requireMember()

    applicationKey match {

      case None => Future {
        val tpl = request.mainTemplate(Some(Util.AddApplicationText))
        val filledForm = uploadForm.fill(
          UploadData(
            version = DefaultVersion,
            visibility = Visibility.Organization.toString
          )
        )
        Ok(views.html.versions.form(tpl, applicationKey, filledForm))
      }

      case Some(key) => {
        for {
          applicationResponse <- request.api.Applications.getByOrgKey(orgKey = orgKey, key = Some(key))
          versionsResponse <- request.api.versions.getByOrgKeyAndApplicationKey(orgKey, key, limit = Some(1))
        } yield {
          applicationResponse.headOption match {
            case None => {
              Redirect(routes.Organizations.show(orgKey)).flashing("warning" -> s"Application not found: ${key}")
            }
            case Some(application) => {
              val tpl = request.mainTemplate(Some(s"${application.name}: Upload new version")).copy(
                application = Some(application),
                version = versionsResponse.headOption.map(_.version)
              )
              val filledForm = uploadForm.fill(
                UploadData(
                  version = versionsResponse.headOption.map(v => VersionTag(v.version).nextMicro().getOrElse(v.version)).getOrElse(DefaultVersion),
                  visibility = application.visibility.toString
                )
              )
              Ok(views.html.versions.form(tpl, applicationKey, filledForm))
            }
          }
        }
      }
    }
  }

  def createPost(
    orgKey: String,
    applicationKey: Option[String] = None
  ) = AuthenticatedOrg.async(parse.multipartFormData) { implicit request =>
    request.requireMember()

    val tpl = applicationKey match {
      case None => request.mainTemplate(Some(Util.AddApplicationText))
      case Some(key) => request.mainTemplate(Some("Upload New Version"))
    }
    val boundForm = uploadForm.bindFromRequest
    boundForm.fold (

      errors => Future {
        Ok(views.html.versions.form(tpl, applicationKey, errors))
      },

      valid => {

        request.body.file("file") match {
          case None => Future {
            Ok(views.html.versions.form(tpl, applicationKey, boundForm, Seq("Please select a non empty file to upload")))
          }

          case Some(file) => {
            val path = File.createTempFile("api", "json")
            file.ref.moveTo(path, true)
            val versionForm = VersionForm(
              originalForm = OriginalForm(
                data = scala.io.Source.fromFile(path, "UTF-8").getLines.mkString("\n").trim
              ),
              Some(Visibility(valid.visibility))
            )

            applicationKey match {
              case None => {
                request.api.versions.postByOrgKeyAndVersion(
                  orgKey = request.org.key,
                  version = valid.version,
                  versionForm = versionForm
                ).map { version =>
                  Redirect(routes.Versions.show(version.organization.key, version.application.key, version.version)).flashing( "success" -> "Application version updated" )
                }.recover {
                  case r: com.gilt.apidoc.v0.errors.ErrorsResponse => {
                    Ok(views.html.versions.form(tpl, applicationKey, boundForm, r.errors.map(_.message)))
                  }
                }
              }

              case Some(key) => {
                request.api.versions.putByOrgKeyAndApplicationKeyAndVersion(
                  orgKey = request.org.key,
                  applicationKey = key,
                  version = valid.version,
                  versionForm = versionForm
                ).map { version =>
                  Redirect(routes.Versions.show(version.organization.key, version.application.key, version.version)).flashing( "success" -> "Application version created" )
                }.recover {
                  case r: com.gilt.apidoc.v0.errors.ErrorsResponse => {
                    Ok(views.html.versions.form(tpl, applicationKey, boundForm, r.errors.map(_.message)))
                  }
                }
              }
            }
          }
        }
      }
    )
  }

  private def isWatching(
    api: com.gilt.apidoc.v0.Client,
    user: Option[User],
    orgKey: String,
    applicationKey: String
  ): Future[Boolean] = {
    user match {
      case None => Future { false }
      case Some(u) => {
        api.watches.get(
          userGuid = Some(u.guid),
          organizationKey = Some(orgKey),
          applicationKey = Some(applicationKey)
        ).map { watches =>
          watches match {
            case Nil => false
            case _ => true
          }
        }
      }
    }
  }

  case class UploadData(version: String, visibility: String)
  private val uploadForm = Form(
    mapping(
      "version" -> nonEmptyText,
      "visibility" -> nonEmptyText
    )(UploadData.apply)(UploadData.unapply)
  )

}
