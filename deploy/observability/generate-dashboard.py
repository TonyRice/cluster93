#!/usr/bin/env python3
"""Writes grafana/dashboards/cluster93.json. Run it after changing a panel; DashboardTest checks every metric it uses exists."""
import json, os

panels, y = [], 0

def row(title):
    global y
    panels.append({"type": "row", "title": title, "collapsed": False, "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})
    y += 1

def panel(title, kind, targets, w=8, h=7, unit=None, x=None, description=None):
    global y
    if x is None:
        used = sum(p["gridPos"]["w"] for p in panels if p["gridPos"]["y"] == y and p["type"] != "row")
        x = used
        if x + w > 24:
            y += h
            x = 0
    p = {"type": kind, "title": title, "datasource": {"type": "prometheus", "uid": "prometheus"},
         "gridPos": {"h": h, "w": w, "x": x, "y": y},
         "targets": [{"refId": chr(65 + i), "expr": expr, "legendFormat": legend, "datasource": {"type": "prometheus", "uid": "prometheus"}}
                     for i, (expr, legend) in enumerate(targets)],
         "fieldConfig": {"defaults": {"unit": unit or "short"}, "overrides": []}, "options": {}}
    if description:
        p["description"] = description
    panels.append(p)
    if x + w >= 24:
        y += h

row("Cluster")
panel("Live nodes", "stat", [("max(c93_cluster_nodes)", "nodes")], w=4)
panel("Leader", "stat", [("max by (node) (c93_cluster_leader) == 1", "{{node}}")], w=4,
      description="The node running the reaper, supervisor and garbage collection (the lowest instance id).")
panel("Ignite data region used", "timeseries", [("c93_ignite_data_region_used_bytes", "{{node}}")], w=8, unit="bytes")
panel("Heap used", "timeseries", [('sum by (node) (jvm_memory_used_bytes{area="heap"})', "{{node}}")], w=8, unit="bytes")

row("Jobs")
panel("Submitted / s", "timeseries", [("sum by (kind) (rate(c93_jobs_submitted_total[1m]))", "{{kind}}")], unit="ops")
panel("Finished / s by status", "timeseries", [("sum by (status) (rate(c93_jobs_duration_seconds_count[1m]))", "{{status}}")], unit="ops")
panel("Running vs capacity", "timeseries", [("sum(c93_jobs_running)", "running"), ("sum(c93_jobs_capacity)", "capacity")])
panel("Queue wait (p50 / p95)", "timeseries", [
    ("histogram_quantile(0.5, sum by (le) (rate(c93_jobs_queue_wait_seconds_bucket[5m])))", "p50"),
    ("histogram_quantile(0.95, sum by (le) (rate(c93_jobs_queue_wait_seconds_bucket[5m])))", "p95")], unit="s")
panel("Run time p95 by status", "timeseries", [
    ("histogram_quantile(0.95, sum by (le, status) (rate(c93_jobs_duration_seconds_bucket[5m])))", "{{status}}")], unit="s")
panel("Isolate cold start (p50 / p95)", "timeseries", [
    ("histogram_quantile(0.5, sum by (le) (rate(c93_isolates_start_seconds_bucket[5m])))", "p50"),
    ("histogram_quantile(0.95, sum by (le) (rate(c93_isolates_start_seconds_bucket[5m])))", "p95")], unit="s")

row("Services")
panel("Service runs started / min", "timeseries", [("sum by (restart) (rate(c93_services_runs_total[5m])) * 60", "restart={{restart}}")], w=12)
panel("Event bus messages / s", "timeseries", [("sum(rate(vertx_eventbus_processed_total[1m]))", "processed"),
                                                ("sum(vertx_eventbus_pending)", "pending")], w=12)

row("HTTP")
panel("API requests / s by status", "timeseries", [('sum by (code) (rate(vertx_http_server_requests_total{route!=""}[1m]))', "{{code}}")], unit="reqps")
panel("API latency (avg / max)", "timeseries", [
    ('sum(rate(vertx_http_server_response_time_seconds_sum{route!=""}[1m])) / sum(rate(vertx_http_server_response_time_seconds_count{route!=""}[1m]))', "avg"),
    ('max(vertx_http_server_response_time_seconds_max{route!=""})', "max")], unit="s")
panel("Rate limited / s by tenant", "timeseries", [("sum by (tenant) (rate(c93_api_rate_limited_total[1m]))", "{{tenant}}")], unit="reqps")
panel("Ingress requests / s by status", "timeseries", [('sum by (code) (rate(vertx_http_server_requests_total{route="", local=~".*:9394"}[1m]))', "{{code}}")],
      unit="reqps", description="Services' public traffic (the ingress port, 9394 in the demo).")
panel("Ingress bytes / s", "timeseries", [('sum(rate(vertx_http_server_bytes_read_total{local=~".*:9394"}[1m]))', "in"),
                                          ('sum(rate(vertx_http_server_bytes_written_total{local=~".*:9394"}[1m]))', "out")], unit="Bps")
panel("Open connections", "timeseries", [("sum by (local) (vertx_http_server_active_connections)", "{{local}}")])

row("Storage")
panel("Bytes stored / s by backend", "timeseries", [("sum by (backend) (rate(c93_files_stored_bytes_total[5m]))", "{{backend}}")], unit="Bps")
panel("Uploads refused / min by reason", "timeseries", [("sum by (reason) (rate(c93_files_refused_total[5m])) * 60", "{{reason}}")])
panel("Worker pools in use", "timeseries", [("sum by (pool_name) (vertx_pool_in_use)", "{{pool_name}}")])

row("Queues and callbacks")
panel("Queue messages / s by outcome", "timeseries", [("sum by (outcome) (rate(c93_queue_messages_total[1m]))", "{{outcome}}")],
      w=12, unit="ops", description="pushed, claimed, acked; retried when a handler failed; reclaimed when a consumer's node died "
                                    "and its lease ran out; released when its script stopped; dead when attempts ran out (D-061)")
panel("Callback deliveries / min by outcome", "timeseries",
      [("sum by (outcome) (rate(c93_callbacks_deliveries_total[5m])) * 60", "{{outcome}}")], w=12,
      description="Completion callbacks: delivered, retry (tried again later) or failed (given up) (D-062)")

dashboard = {"uid": "c93", "title": "Cluster93", "tags": ["c93"], "timezone": "browser", "schemaVersion": 39,
             "version": 1, "refresh": "5s", "time": {"from": "now-15m", "to": "now"}, "panels": panels}
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "grafana", "dashboards", "cluster93.json")
with open(out, "w") as f:
    json.dump(dashboard, f, indent=2)
    f.write("\n")
print(f"wrote {len(panels)} panels to {out}")
