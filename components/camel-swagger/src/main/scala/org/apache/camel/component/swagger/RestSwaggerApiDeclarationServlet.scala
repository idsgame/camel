/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.swagger

import javax.servlet.http.{HttpServlet, HttpServletResponse, HttpServletRequest}
import javax.servlet.ServletConfig

import com.wordnik.swagger.core.SwaggerContext
import com.wordnik.swagger.core.filter.SpecFilter
import com.wordnik.swagger.core.util.JsonSerializer
import com.wordnik.swagger.config.{SwaggerConfig, ConfigFactory, FilterFactory}
import com.wordnik.swagger.model.{ApiInfo, ResourceListing, ApiListingReference}

import org.springframework.web.context.support.WebApplicationContextUtils
import org.springframework.web.context.WebApplicationContext
import org.apache.camel.CamelContext
import org.slf4j.LoggerFactory

class RestSwaggerApiDeclarationServlet extends HttpServlet {

  // TODO: this has spring dependency, find a way to make it work with blueprint/spring/cdi/servlet-listener etc

  private val LOG = LoggerFactory.getLogger(classOf[RestSwaggerApiDeclarationServlet])

  var spring: WebApplicationContext = null
  val reader = new RestSwaggerReader()
  var camel: CamelContext = null
  val swaggerConfig: SwaggerConfig = ConfigFactory.config

  override def init(config: ServletConfig): Unit = {
    super.init(config)
    LOG.info("init")

    // configure swagger options
    var s = config.getInitParameter("api.version")
    if (s != null) {
      swaggerConfig.setApiVersion(s)
    }
    s = config.getInitParameter("swagger.version")
    if (s != null) {
      swaggerConfig.setSwaggerVersion(s)
    }
    s = config.getInitParameter("base.path")
    if (s != null) {
      swaggerConfig.setBasePath(s)
    }
    s = config.getInitParameter("api.path")
    if (s != null) {
      swaggerConfig.setApiPath(s)
    }

    val title = config.getInitParameter("api.title")
    val description = config.getInitParameter("api.description")
    val termsOfServiceUrl = config.getInitParameter("api.termsOfServiceUrl")
    val contact = config.getInitParameter("api.contact")
    val license = config.getInitParameter("api.license")
    val licenseUrl = config.getInitParameter("api.licenseUrl")

    val apiInfo = new ApiInfo(title, description, termsOfServiceUrl, contact, license, licenseUrl)
    swaggerConfig.setApiInfo(apiInfo)

    spring = WebApplicationContextUtils.getRequiredWebApplicationContext(config.getServletContext)
    if (spring != null) {
      camel = spring.getBean(classOf[CamelContext])
      if (camel != null) {
        // TODO: if this is not sufficient we need to use Camel's resolveClass API instead
        SwaggerContext.registerClassLoader(camel.getApplicationContextClassLoader)
      }
    }

    LOG.info("init found spring {}", spring)
  }

  override protected def doGet(request: HttpServletRequest, response: HttpServletResponse) = {
    val route = request.getPathInfo
    // render overview if the route is empty or is the root path
    if (route != null && route != "" && route != "/") {
      renderApiDeclaration(request, response)
    } else {
      renderResourceListing(request, response)
    }
  }

  /**
   * Renders the resource listing which is the overview of all the apis
   */
  def renderResourceListing(request: HttpServletRequest, response: HttpServletResponse) = {
    LOG.info("renderResourceListing")

    val queryParams = Map[String, List[String]]()
    val cookies = Map[String, String]()
    val headers = Map[String, List[String]]()

    LOG.info("renderResourceListing camel -> {}", camel)
    if (camel != null) {
      val f = new SpecFilter
      val listings = RestApiListingCache.listing(camel, swaggerConfig).map(specs => {
        (for (spec <- specs.values)
        yield f.filter(spec, FilterFactory.filter, queryParams, cookies, headers)
          ).filter(m => m.apis.size > 0)
      })
      val references = (for (listing <- listings.getOrElse(List())) yield {
        ApiListingReference(listing.resourcePath, listing.description)
      }).toList
      val resourceListing = ResourceListing(
        swaggerConfig.apiVersion,
        swaggerConfig.swaggerVersion,
        references,
        List(),
        swaggerConfig.info
      )
      LOG.info("renderResourceListing write response -> {}", resourceListing)
      response.getOutputStream.write(JsonSerializer.asJson(resourceListing).getBytes("utf-8"))
    } else {
      response.setStatus(204)
    }
  }

  /**
   * Renders the api listing of a single resource
   */
  def renderApiDeclaration(request: HttpServletRequest, response: HttpServletResponse) = {
    val route = request.getPathInfo
    val docRoot = request.getPathInfo
    val f = new SpecFilter
    val queryParams = Map[String, List[String]]()
    val cookies = Map[String, String]()
    val headers = Map[String, List[String]]()
    val pathPart = docRoot

    LOG.info("renderApiDeclaration camel -> {}", camel)
    if (camel != null) {
      val listings = RestApiListingCache.listing(camel, swaggerConfig).map(specs => {
          (for (spec <- specs.values) yield {
          f.filter(spec, FilterFactory.filter, queryParams, cookies, headers)
        }).filter(m => m.resourcePath == pathPart)
      }).toList.flatten
      listings.size match {
        case 1 => {
          LOG.info("renderResourceListing write response -> {}", listings.head)
          response.getOutputStream.write(JsonSerializer.asJson(listings.head).getBytes("utf-8"))
        }
        case _ => response.setStatus(404)
      }
    } else {
      // no data
      response.setStatus(204)
    }
  }

}
