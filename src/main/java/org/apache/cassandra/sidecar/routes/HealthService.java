/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.sidecar.routes;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.common.collect.ImmutableMap;

import com.google.inject.Singleton;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.json.Json;

/**
 * Provides a simple REST endpoint to determine if Sidecar is available
 */
@Singleton
@Path("/api/v1/__health")
public class HealthService
{
    @Operation(summary = "Health Check for Sidecar's status",
    description = "Returns HTTP 200 if Sidecar is available")
    @Produces(MediaType.APPLICATION_JSON)
    @GET
    public Response getSidecarHealth()
    {
        return Response.status(HttpResponseStatus.OK.code()).entity(Json.encode(ImmutableMap.of("status", "OK")))
                       .build();
    }
}
