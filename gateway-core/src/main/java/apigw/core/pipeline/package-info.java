/**
 * The per-event-loop request pipeline: protocol validation, snapshot read, routing, plugin chain,
 * forwarding header rewrite, then hand-off to a {@link apigw.core.pipeline.Forwarder}.
 */
package apigw.core.pipeline;
