/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Abstract resource creation, for a generic resource type {@code R}.
 * This class applies the template method pattern, first checking whether the resource exists,
 * and creating it if it does not. It is not an error if the resource did already exist.
 * @param <C> The type of client used to interact with kubernetes.
 * @param <T> The Kubernetes resource type.
 * @param <L> The list variant of the Kubernetes resource type.
 * @param <D> The doneable variant of the Kubernetes resource type.
 * @param <R> The resource operations.
 */
public abstract class AbstractResourceOperator<C extends KubernetesClient, T extends HasMetadata,
        L extends KubernetesResourceList/*<T>*/, D, R extends Resource<T, D>> {

    protected final Logger log = LogManager.getLogger(getClass());
    protected final Vertx vertx;
    protected final C client;
    protected final String resourceKind;

    /**
     * Constructor.
     * @param vertx The vertx instance.
     * @param client The kubernetes client.
     * @param resourceKind The mind of Kubernetes resource (used for logging).
     */
    public AbstractResourceOperator(Vertx vertx, C client, String resourceKind) {
        this.vertx = vertx;
        this.client = client;
        this.resourceKind = resourceKind;
    }

    protected abstract MixedOperation<T, L, D, R> operation();

    /**
     * Asynchronously create or update the given {@code resource} depending on whether it already exists,
     * returning a future for the outcome.
     * If the resource with that name already exists the future completes successfully.
     * @param resource The resource to create.
     */
    public Future<ReconcileResult<T>> createOrUpdate(T resource) {
        if (resource == null) {
            throw new NullPointerException();
        }
        return reconcile(resource.getMetadata().getNamespace(), resource.getMetadata().getName(), resource);
    }

    /**
     * Asynchronously reconciles the resource with the given namespace and name to match the given
     * desired resource, returning a future for the result.
     */
    public Future<ReconcileResult<T>> reconcile(String namespace, String name, T desired) {
        if (desired != null && !namespace.equals(desired.getMetadata().getNamespace())) {
            return Future.failedFuture("Given namespace " + namespace + " incompatible with desired namespace " + desired.getMetadata().getNamespace());
        } else if (desired != null && !name.equals(desired.getMetadata().getName())) {
            return Future.failedFuture("Given name " + name + " incompatible with desired name " + desired.getMetadata().getName());
        }

        Future<ReconcileResult<T>> fut = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
            future -> {
                T current = operation().inNamespace(namespace).withName(name).get();
                if (desired != null) {
                    if (current == null) {
                        log.debug("{} {}/{} does not exist, creating it", resourceKind, namespace, name);
                        internalCreate(namespace, name, desired).setHandler(future);
                    } else {
                        log.debug("{} {}/{} already exists, patching it", resourceKind, namespace, name);
                        internalPatch(namespace, name, current, desired).setHandler(future);
                    }
                } else {
                    if (current != null) {
                        // Deletion is desired
                        log.debug("{} {}/{} exist, deleting it", resourceKind, namespace, name);
                        internalDelete(namespace, name).setHandler(future);
                    } else {
                        log.debug("{} {}/{} does not exist, noop", resourceKind, namespace, name);
                        future.complete(ReconcileResult.noop(null));
                    }
                }

            },
            false,
            fut.completer()
        );
        return fut;
    }

    /**
     * Deletes the resource with the given namespace and name and completes the given future accordingly.
     * This method will do a cascading delete.
     *
     * @param namespace Namespace of the resource which should be deleted
     * @param name Name of the resource which should be deleted
     *
     * @return Future with result of the reconciliation
     */
    protected Future<ReconcileResult<T>> internalDelete(String namespace, String name) {
        return internalDelete(namespace, name, true);
    }

    /**
     * Deletes the resource with the given namespace and name and completes the given future accordingly
     *
     * @param namespace Namespace of the resource which should be deleted
     * @param name Name of the resource which should be deleted
     * @param cascading Defines whether the delete should be cascading or not (e.g. whether a STS deletion should delete pods etc.)
     *
     * @return Future with result of the reconciliation
     */

    protected Future<ReconcileResult<T>> internalDelete(String namespace, String name, boolean cascading) {
        try {
            operation().inNamespace(namespace).withName(name).cascading(cascading).delete();
            log.debug("{} {} in namespace {} has been deleted", resourceKind, name, namespace);
            return Future.succeededFuture(ReconcileResult.deleted());
        } catch (Exception e) {
            log.debug("Caught exception while deleting {} {} in namespace {}", resourceKind, name, namespace, e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Patches the resource with the given namespace and name to match the given desired resource
     * and completes the given future accordingly.
     */
    protected Future<ReconcileResult<T>> internalPatch(String namespace, String name, T current, T desired) {
        return internalPatch(namespace, name, current, desired, true);
    }

    protected Future<ReconcileResult<T>> internalPatch(String namespace, String name, T current, T desired, boolean cascading) {
        try {
            T result = operation().inNamespace(namespace).withName(name).cascading(cascading).patch(desired);
            log.debug("{} {} in namespace {} has been patched", resourceKind, name, namespace);
            return Future.succeededFuture(wasChanged(current, result) ? ReconcileResult.patched(result) : ReconcileResult.noop(result));
        } catch (Exception e) {
            log.debug("Caught exception while patching {} {} in namespace {}", resourceKind, name, namespace, e);
            return Future.failedFuture(e);
        }
    }

    protected boolean wasChanged(T oldVersion, T newVersion) {
        if (oldVersion != null
                && oldVersion.getMetadata() != null
                && newVersion != null
                && newVersion.getMetadata() != null) {
            return !Objects.equals(oldVersion.getMetadata().getResourceVersion(), newVersion.getMetadata().getResourceVersion());
        } else {
            return true;
        }
    }

    /**
     * Creates a resource with the given namespace and name with the given desired state
     * and completes the given future accordingly.
     */
    protected Future<ReconcileResult<T>> internalCreate(String namespace, String name, T desired) {
        try {
            ReconcileResult<T> result = ReconcileResult.created(operation().inNamespace(namespace).withName(name).create(desired));
            log.debug("{} {} in namespace {} has been created", resourceKind, name, namespace);
            return Future.succeededFuture(result);
        } catch (Exception e) {
            log.debug("Caught exception while creating {} {} in namespace {}", resourceKind, name, namespace, e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Synchronously gets the resource with the given {@code name} in the given {@code namespace}.
     * @param namespace The namespace.
     * @param name The name.
     * @return The resource, or null if it doesn't exist.
     */
    public T get(String namespace, String name) {
        return operation().inNamespace(namespace).withName(name).get();
    }

    /**
     * Asynchronously gets the resource with the given {@code name} in the given {@code namespace}.
     * @param namespace The namespace.
     * @param name The name.
     * @return A Future for the result.
     */
    public Future<T> getAsync(String namespace, String name) {
        Future<T> result = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-tool").executeBlocking(
            future -> {
                T resource = get(namespace, name);
                future.complete(resource);
            }, true, result.completer()
        );
        return result;
    }

    /**
     * Synchronously list the resources in the given {@code namespace} with the given {@code selector}.
     * @param namespace The namespace.
     * @param selector The selector.
     * @return A list of matching resources.
     */
    @SuppressWarnings("unchecked")
    public List<T> list(String namespace, Labels selector) {
        if (AbstractWatchableResourceOperator.ANY_NAMESPACE.equals(namespace))  {
            return listInAnyNamespace(selector);
        } else {
            return listInNamespace(namespace, selector);
        }
    }

    protected List<T> listInAnyNamespace(Labels selector) {
        FilterWatchListMultiDeletable<T, L, Boolean, Watch, Watcher<T>> operation = operation().inAnyNamespace();

        if (selector != null) {
            Map<String, String> labels = selector.toMap();
            return operation.withLabels(labels)
                    .list()
                    .getItems();
        } else {
            return operation
                    .list()
                    .getItems();
        }
    }

    protected List<T> listInNamespace(String namespace, Labels selector) {
        NonNamespaceOperation<T, L, D, R> tldrNonNamespaceOperation = operation().inNamespace(namespace);

        if (selector != null) {
            Map<String, String> labels = selector.toMap();
            return tldrNonNamespaceOperation.withLabels(labels)
                    .list()
                    .getItems();
        } else {
            return tldrNonNamespaceOperation
                    .list()
                    .getItems();
        }
    }

    /**
     * Asynchronously lists the resource with the given {@code selector} in the given {@code namespace}.
     *
     * @param namespace The namespace.
     * @param selector The selector.
     * @return A Future with a list of matching resources.
     */
    public Future<List<T>> listAsync(String namespace, Labels selector) {
        Future<List<T>> result = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-tool").executeBlocking(
            future -> {
                List<T> resources;

                if (AbstractWatchableResourceOperator.ANY_NAMESPACE.equals(namespace))  {
                    resources = listInAnyNamespace(selector);
                } else {
                    resources = listInNamespace(namespace, selector);
                }

                future.complete(resources);
            }, true, result.completer()
        );
        return result;
    }

    /**
     * Returns a future that completes when the resource identified by the given {@code namespace} and {@code name}
     * is ready.
     *
     * @param namespace The namespace.
     * @param name The resource name.
     * @param pollIntervalMs The poll interval in milliseconds.
     * @param timeoutMs The timeout, in milliseconds.
     * @param predicate The predicate.
     */
    public Future<Void> waitFor(String namespace, String name, long pollIntervalMs, final long timeoutMs, BiPredicate<String, String> predicate) {
        return Util.waitFor(vertx,
            String.format("%s resource %s in namespace %s", resourceKind, name, namespace),
            pollIntervalMs,
            timeoutMs,
            () -> predicate.test(namespace, name));
    }

    private Future<Void> closeAsync(java.io.Closeable c) {
        Future<Void> result = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-tool").executeBlocking(
            fut -> {
                try {
                    c.close();
                    log.debug("Closed {}", c);
                    fut.complete();
                } catch (Throwable t) {
                    log.warn("Ignoring error closing {}", c);
                    fut.fail(t);
                }
            },
            true,
            result);
        return result;
    }

    /**
     * Returns a Future which is completed when the given {@code predicate} returns true for the resource given by
     * {@code namespace} and {@code name}.
     * The predicate will be called once initially and whenever the given resource changes.
     * Unlike {@link #waitFor(String, String, long, long, BiPredicate)} this makes use of watches rather than
     * polling.
     *
     * @param logContext A string to use in log messages
     * @param namespace The namespace of the resource to watch
     * @param name The name of the resource to watch
     * @param timeoutMs The timeout. in milliseconds. The returned Future will after after approximately this
     *                  time if it hasn't yet been satisfied.
     * @param predicate The predicate to be satisfied.
     * @return A future which will be completed when the predicate returns true,
     * or after approximately {@code timeoutMs} milliseconds.
     */
    public Future<Void> watchFor(String logContext, String namespace, String name, long timeoutMs, Predicate<T> predicate) {
        AtomicReference<Watch> watchReference = new AtomicReference<>();
        Future<Void> conditionResult = Future.future();
        long timerId = vertx.setTimer(timeoutMs, tid -> {
            log.warn("{}: Timeout after {}ms", logContext, timeoutMs);
            conditionResult.tryFail(new TimeoutException("Timeout '" + logContext + "' after " + timeoutMs + "ms"));
        });
        log.debug("{}: Opening watch on {} {}/{}", logContext, this.resourceKind, namespace, name);
        // Set up the watchReference
        Future<Watch> watchFuture = getWatchFuture(namespace, name, new Watcher<T>() {
            @Override
            public void eventReceived(Action action, T resource) {
                try {
                    log.debug("{}: Received {} event", logContext, action);
                    if (predicate.test(resource)) {
                        log.debug("{}: Predicate satisfied by {} event", logContext, action);
                        conditionResult.tryComplete();
                    } else {
                        log.trace("{}: Predicate not satisfied by {} event", logContext, action);
                    }
                } catch (Throwable t) {
                    log.warn("{}: Predicate threw {} for event {}", logContext, t.toString(), action);
                    conditionResult.tryFail(t);
                }
            }

            @Override
            public void onClose(KubernetesClientException cause) {
            }
        });
        watchFuture.setHandler(ar -> {
            if (ar.succeeded()) {
                // Watch is set up, but the predicate could have been satisfied before the watchReference was set up
                // and there might not be further events before the timeout, so get the resource and apply the predicate.
                Watch watch = ar.result();
                log.debug("{}: Opened watch {}", logContext, watch);
                watchReference.set(watch);
                getAsync(namespace, name).map(resource -> {
                    try {
                        if (predicate.test(resource)) {
                            log.debug("{}: Condition satisfied by post-watch get", logContext);
                            conditionResult.tryComplete();
                        } else {
                            log.trace("{}: Predicate not satisfied by post-watch get", logContext);
                        }
                    } catch (Throwable t) {
                        log.warn("{}: Predicate threw {} for post-watch get", logContext, t.toString());
                        conditionResult.tryFail(t);
                    }
                    return resource;
                });
            } else {
                conditionResult.fail(ar.cause());
            }
        });

        Future<Void> result = Future.future();
        conditionResult.setHandler(ar -> {
            vertx.cancelTimer(timerId);
            Watch watch = watchReference.get();
            if (watchReference != null) {
                // NOTE: The close happens asynchronously
                log.debug("{}: Closing {}", logContext, watch);
                closeAsync(watch);
            }
            if (ar.succeeded()) {
                result.complete();
            } else {
                result.fail(ar.cause());
            }
        });
        return result;
    }

    Future<Watch> getWatchFuture(String namespace, String name, Watcher<T> watcher) {
        Future<Watch> watchFuture = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-tool").<Watch>executeBlocking(
            fut -> {
                try {
                    Watch watch = operation().inNamespace(namespace).withName(name).watch(watcher);
                    fut.complete(watch);
                } catch (Throwable t) {
                    fut.fail(t);
                }
            },
            true,
            watchFuture);
        return watchFuture;
    }
}
