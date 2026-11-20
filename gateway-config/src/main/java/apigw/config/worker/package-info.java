/**
 * The etcd config worker: full sync at one revision, watch with debounce, atomic snapshot publish,
 * last-known-good persistence and the leased node heartbeat. Runs on one Vert.x worker context,
 * never on a traffic event loop. See ADR 0014.
 */
package apigw.config.worker;
