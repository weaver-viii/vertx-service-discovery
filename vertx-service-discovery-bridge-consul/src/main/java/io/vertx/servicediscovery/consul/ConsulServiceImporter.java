/*
 * Copyright (c) 2011-2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.servicediscovery.consul;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.impl.ServiceTypes;
import io.vertx.servicediscovery.spi.ServiceImporter;
import io.vertx.servicediscovery.spi.ServicePublisher;
import io.vertx.servicediscovery.spi.ServiceType;
import io.vertx.servicediscovery.types.HttpLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * A discovery bridge importing services from Consul.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class ConsulServiceImporter implements ServiceImporter {

  private ServicePublisher publisher;
  private HttpClient client;

  private final static Logger LOGGER = LoggerFactory.getLogger(ConsulServiceImporter.class);

  private final List<ImportedConsulService> imports = new ArrayList<>();
  private String dc;
  private long scanTask = -1;
  private Vertx vertx;

  @Override
  public void start(Vertx vertx, ServicePublisher publisher, JsonObject configuration, Future<Void> completion) {
    this.vertx = vertx;
    this.publisher = publisher;

    HttpClientOptions options = new HttpClientOptions(configuration);
    String host = configuration.getString("host", "localhost");
    int port = configuration.getInteger("port", 8500);

    options.setDefaultHost(host);
    options.setDefaultPort(port);

    dc = configuration.getString("dc");
    client = vertx.createHttpClient(options);

    Future<Void> imports = Future.future();

    retrieveServicesFromConsul(imports);

    imports.setHandler(ar -> {
      if (ar.succeeded()) {
        Integer period = configuration.getInteger("scan-period", 2000);
        if (period != 0) {
          scanTask = vertx.setPeriodic(period, l -> {
            Future<Void> future = Future.future();
            future.setHandler(ar2 -> {
              if (ar2.failed()) {
                LOGGER.warn("Consul importation has failed", ar.cause());
              }
            });
            retrieveServicesFromConsul(future);
          });
        }

        completion.complete();
      } else {
        completion.fail(ar.cause());
      }
    });

  }


  private Handler<Throwable> getErrorHandler(Future future) {
    return t -> {
      if (future != null) {
        future.fail(t);
      } else {
        LOGGER.error(t);
      }
    };
  }

  private void retrieveServicesFromConsul(Future<Void> completed) {
    String path = "/v1/catalog/services";
    if (dc != null) {
      path += "?dc=" + dc;
    }

    Handler<Throwable> error = getErrorHandler(completed);

    client.get(path)
        .exceptionHandler(error)
        .handler(response -> {
          response
              .exceptionHandler(error)
              .bodyHandler(buffer -> {
                retrieveIndividualServices(buffer.toJsonObject(), completed);
              });
        })
        .end();
  }

  private void retrieveIndividualServices(JsonObject jsonObject, Future<Void> completed) {
    List<String> ids = new ArrayList<>();

    List<Future> futures = new ArrayList<>();
    jsonObject.fieldNames().forEach(name -> {
      Future<Void> future = Future.future();
      Handler<Throwable> error = getErrorHandler(future);
      String path = "/v1/catalog/service/" + name;
      if (dc != null) {
        path += "?dc=" + dc;
      }


      client.get(path)
          .exceptionHandler(error)
          .handler(response -> {
            response.exceptionHandler(error)
                .bodyHandler(buffer -> {
                  List<String> id = importService(buffer.toJsonArray(), future);
                  if (id != null && !id.isEmpty()) {
                    ids.addAll(id);
                  }
                });
          })
          .end();

      futures.add(future);
    });

    CompositeFuture.all(futures).setHandler(ar -> {
      if (ar.failed()) {
        LOGGER.error("Fail to retrieve the services from consul", ar.cause());
      } else {
        List<ImportedConsulService> toRemove = new ArrayList<>();
        imports.stream().filter(svc -> !ids.contains(svc.id())).forEach(svc -> {
          toRemove.add(svc);
          svc.unregister(publisher, null);
        });
        imports.removeAll(toRemove);
      }

      if (ar.succeeded()) {
        completed.complete();
      } else {
        completed.fail(ar.cause());
      }
    });
  }

  private List<String> importService(JsonArray array, Future<Void> future) {
    if (array.isEmpty()) {
      Future.failedFuture("no service with the given name");
      return null;
    } else {
      List<String> ids = new ArrayList<>();
      List<Future> registrations = new ArrayList<>();
      for (int i = 0; i < array.size(); i++) {
        Future<Void> registration = Future.future();
        registrations.add(future);
        JsonObject jsonObject = array.getJsonObject(i);
        String address = jsonObject.getString("Address");
        String name = jsonObject.getString("ServiceName");
        String id = jsonObject.getString("ServiceID");

        JsonArray tags = jsonObject.getJsonArray("ServiceTags");
        if (tags == null) {
          tags = new JsonArray();
        }

        String path = jsonObject.getString("ServiceAddress");
        int port = jsonObject.getInteger("ServicePort");

        JsonObject metadata = jsonObject.copy();
        tags.stream().forEach(tag -> metadata.put((String) tag, true));

        Record record = new Record()
            .setName(name)
            .setMetadata(metadata);

        // To determine the record type, check if we have a tag with a "type" name
        record.setType(ServiceType.UNKNOWN);
        ServiceTypes.all().forEachRemaining(type -> {
          if (metadata.getBoolean(type.name(), false)) {
            record.setType(type.name());
          }
        });

        JsonObject location = new JsonObject();
        location.put("host", address);
        location.put("port", port);
        if (path != null) {
          location.put("path", path);
        }

        // Manage HTTP endpoint
        if (record.getType().equals("http-endpoint")) {
          if (path != null) {
            location.put("root", path);
          }
          if (metadata.getBoolean("ssl", false)) {
            location.put("ssl", true);
          }
          location = new HttpLocation(location).toJson();
        }

        record.setLocation(location);

        // the id must be unique, so check if the service has already being imported
        ImportedConsulService imported = getImportedServiceById(id);
        if (imported != null) {
          future.complete();
        } else {
          LOGGER.info("Importing service " + record.getName() + " (" + record.getMetadata().getString("ServiceID")
              + ") from consul");
          imports.add(new ImportedConsulService(name, id, record).register(publisher, registration));
        }

        ids.add(id);
      }

      CompositeFuture.all(registrations).setHandler(ar -> {
        if (ar.succeeded()) {
          future.complete(null);
        } else {
          future.fail(ar.cause());
        }
      });

      return ids;
    }
  }

  private ImportedConsulService getImportedServiceById(String id) {
    for (ImportedConsulService svc : imports) {
      if (svc.id().equals(id)) {
        return svc;
      }
    }
    return null;
  }

  @Override
  public void close(Handler<Void> completionHandler) {
    if (scanTask != -1) {
      vertx.cancelTimer(scanTask);
    }
    // Remove all the services that has been imported
    List<Future> list = new ArrayList<>();
    imports.forEach(imported -> {
      Future<Void> fut = Future.future();
      fut.setHandler(ar -> {
        LOGGER.info("Unregistering " + imported.name());
        if (ar.succeeded()) {
          list.add(Future.succeededFuture());
        } else {
          list.add(Future.failedFuture(ar.cause()));
        }
      });
      imported.unregister(publisher, fut);
    });

    CompositeFuture.all(list).setHandler(ar -> {
      if (ar.succeeded()) {
        LOGGER.info("Successfully closed the service importer " + this);
      } else {
        LOGGER.error("A failure has been caught while stopping " + this, ar.cause());
      }
      if (completionHandler != null) {
        completionHandler.handle(null);
      }
    });
  }
}
