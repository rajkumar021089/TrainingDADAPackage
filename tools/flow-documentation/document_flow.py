#!/usr/bin/env python3
"""Document reachable webMethods Flow services using Python 3.9+.

Example:
  python document_flow.py --packages /path/to/packages --service demo.api:get --out report

Open report/index.html for the call diagram and ordered steps.
mapping.csv lists explicit field operations; calls.csv lists every call site.
This is static analysis. It never invokes Integration Server.
"""
import argparse
import csv
import html
import re
import sys
from collections import defaultdict, deque
from pathlib import Path
from xml.etree import ElementTree as ET

CALLS = {"INVOKE", "MAPINVOKE"}
MAPPINGS = {"MAPCOPY", "MAPSET", "MAPDELETE", "MAPDROP"}
CONTEXT = {"BRANCH", "SEQUENCE", "LOOP", "REPEAT", "TRY", "CATCH", "FINALLY"}
SERVICE_NAME = re.compile(r"[^\s:]+:[^\s:]+")
CALL_COLUMNS = ("service", "step", "operation", "target", "status", "disabled", "context")
MAP_COLUMNS = ("service", "step", "phase", "target_service", "operation",
               "source", "destination", "assignment", "disabled", "context")


def index_services(packages):
    services = defaultdict(list)
    for package in sorted(packages.iterdir()):
        namespace = package / "ns"
        if not namespace.is_dir():
            continue
        directories = {path.parent for name in ("flow.xml", "node.ndf", "node.idf")
                       for path in namespace.rglob(name)}
        for directory in sorted(directories):
            parts = directory.relative_to(namespace).parts
            if len(parts) >= 2:
                name = ".".join(parts[:-1]) + ":" + parts[-1]
                services[name].append(directory)
    return services


def read_flow(path):
    data = path.read_bytes()
    if re.search(rb"<!\s*(DOCTYPE|ENTITY)\b", data.replace(b"\x00", b""), re.I):
        raise ValueError("DTD and entity declarations are unsupported")
    root = ET.fromstring(data)
    if tag(root) != "FLOW":
        raise ValueError("Expected FLOW root element")
    return root


def tag(element):
    return element.tag.rsplit("}", 1)[-1].upper()


def attributes(element):
    return {key.rsplit("}", 1)[-1].upper(): value for key, value in element.attrib.items()}


def fields(element):
    attrs = attributes(element)
    operation = tag(element)
    source = attrs.get("FROM") or attrs.get("SOURCE", "")
    destination = attrs.get("TO") or attrs.get("TARGET") or attrs.get("FIELD", "")
    if operation in {"MAPDELETE", "MAPDROP"}:
        source, destination = source or destination, ""
    if not source and not destination:
        return "", "", "Unrecognized field encoding"
    if operation == "MAPSET":
        return source, destination, "Literal or expression omitted"
    return source, destination, ""


def walk_steps(element, step="", call="", phase="Pipeline", disabled=False, context=()):
    for position, child in enumerate(element, 1):
        child_step = f"{step}.{position}" if step else str(position)
        operation = tag(child)
        attrs = attributes(child)
        inactive = disabled or attrs.get("DISABLED", "").lower() in {"true", "yes", "1"}
        target = call
        mapping_phase = phase
        branch_context = context
        if operation in CALLS:
            target = attrs.get("SERVICE") or attrs.get("NAME", "")
            mapping_phase = "Call mapping (direction unspecified)"
        if operation == "MAP":
            mode = attrs.get("MODE", "").upper()
            mapping_phase = {"INPUT": "Call input", "OUTPUT": "Call output",
                             "STANDALONE": "Pipeline"}.get(mode, mapping_phase)
        if operation in CONTEXT or attrs.get("LABEL"):
            label = attrs.get("LABEL")
            branch_context += (f"{operation} {label}" if label else operation,)
        yield child, child_step, operation, target, mapping_phase, inactive, " / ".join(branch_context)
        yield from walk_steps(child, child_step, target, mapping_phase, inactive, branch_context)


def status_for(name, services):
    if not SERVICE_NAME.fullmatch(name):
        return "Unresolved or dynamic target"
    locations = services.get(name, [])
    if not locations:
        return "Service not found in supplied packages"
    if len(locations) > 1:
        return "Ambiguous service name"
    if not (locations[0] / "flow.xml").is_file():
        return "Non-Flow service or missing flow.xml"
    return "Flow"


def analyze(parent, packages):
    services = index_services(packages)
    status = status_for(parent, services)
    if status != "Flow":
        raise ValueError(f"{parent}: {status}")
    pending = deque([parent])
    visited = set()
    calls, mappings, steps = [], [], []
    failures = {}
    while pending:
        service = pending.popleft()
        if service in visited:
            continue
        visited.add(service)
        try:
            root = read_flow(services[service][0] / "flow.xml")
        except (ET.ParseError, OSError, ValueError) as exc:
            if service == parent:
                raise ValueError(f"Cannot parse parent Flow: {exc}") from exc
            failures[service] = str(exc)
            continue
        for element, step, operation, target, phase, disabled, context in walk_steps(root):
            if operation == "MAP" or operation in CALLS or operation in CONTEXT or operation in {"EXIT", "THROW"}:
                steps.append((service, step, operation, target if operation in CALLS else "", disabled, context))
            if operation in CALLS:
                status = status_for(target, services)
                calls.append((service, step, operation, target, status, disabled, context))
                if status == "Flow" and not disabled:
                    pending.append(target)
            elif operation in MAPPINGS:
                mappings.append((service, step, phase, target, operation,
                                 *fields(element), disabled, context))
            elif operation.startswith("MAP") and operation != "MAP":
                mappings.append((service, step, phase, target, operation, "", "",
                                 "Unsupported mapping encoding", disabled, context))
    calls = [(caller, step, operation, target,
              "Unreadable Flow" if target in failures else status, disabled, context)
             for caller, step, operation, target, status, disabled, context in calls]
    return calls, mappings, steps, failures


def write_csv(path, columns, rows):
    def cell(value):
        if isinstance(value, str) and value.lstrip().startswith(("=", "+", "-", "@")):
            return "'" + value
        return value

    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(columns)
        writer.writerows([cell(value) for value in row] for row in rows)


def diagram(parent, calls):
    pairs = list(dict.fromkeys((caller, target) for caller, _, _, target, _, disabled, _ in calls
                               if target and not disabled))
    names = dict.fromkeys([parent] + [name for pair in pairs for name in pair])
    links = defaultdict(list)
    for caller, target in pairs:
        links[caller].append(target)
    depth = {parent: 0}
    queue = deque([parent])
    while queue:
        caller = queue.popleft()
        for target in links[caller]:
            if target not in depth:
                depth[target] = depth[caller] + 1
                queue.append(target)
    columns = defaultdict(int)
    coords = {}
    box_width = max([220] + [len(name) * 8 + 28 for name in names])
    spacing = box_width + 80
    for name in names:
        column = depth[name]
        coords[name] = (20 + column * spacing, 20 + columns[column] * 60)
        columns[column] += 1
    width = max(x for x, _ in coords.values()) + box_width + 40
    height = max(y for _, y in coords.values()) + 65
    parts = [f'<svg width="{width}" height="{height}" role="img" aria-label="Service call diagram">',
             '<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" '
             'orient="auto"><path d="M0 0 L8 4 L0 8 Z" fill="#536b8a"/></marker></defs>']
    for caller, target in pairs:
        x, y = coords[caller]
        end_x, end_y = coords[target]
        if end_x > x:
            shape = f"M{x+box_width} {y+19} C{x+box_width+40} {y+19} {end_x-40} {end_y+19} {end_x} {end_y+19}"
        else:
            shape = f"M{x+box_width} {y+26} C{x+box_width+30} {y+50} {end_x+box_width+30} {end_y-8} {end_x+box_width} {end_y+12}"
        parts.append(f'<path d="{shape}" fill="none" stroke="#536b8a" marker-end="url(#arrow)"/>')
    for name, (x, y) in coords.items():
        parts.append(f'<rect x="{x}" y="{y}" width="{box_width}" height="38" rx="6" fill="#e8f1ff" stroke="#7397cb"/>')
        parts.append(f'<text x="{x+12}" y="{y+25}">{html.escape(name)}</text>')
    return "".join(parts) + "</svg>"


def write_html(path, parent, calls, steps, failures):
    def table(columns, rows):
        header = "".join(f"<th>{html.escape(name)}</th>" for name in columns)
        body = "".join("<tr>" + "".join(f"<td>{html.escape(str(value))}</td>" for value in row)
                       + "</tr>" for row in rows)
        return f"<table><thead><tr>{header}</tr></thead><tbody>{body}</tbody></table>"

    report = (
        '<!doctype html><html lang="en"><meta charset="utf-8"><title>Flow documentation</title>'
        '<style>body{font:15px system-ui;margin:2rem;color:#182338}section{overflow:auto}'
        'table{border-collapse:collapse}td,th{border:1px solid #c6d3e3;padding:.45rem;text-align:left}'
        'th{background:#edf4ff}svg text{font:14px monospace}</style>'
        f"<h1>{html.escape(parent)}</h1>"
        '<p><a href="mapping.csv">Download mapping spec</a> · <a href="calls.csv">Download call sites</a></p>'
        '<p>The diagram shows enabled static calls. Step paths reflect XML order and nesting. '
        'Branch conditions, loops, and error handlers determine the actual runtime path. '
        'Implicit pipeline fields and dynamic invocations require runtime verification.</p>'
        f'<h2>Service call diagram</h2><section>{diagram(parent, calls)}</section>'
        f'<h2>Call sites</h2>{table(CALL_COLUMNS, calls)}'
        f'<h2>Ordered Flow steps</h2>{table(("service", "step", "operation", "target", "disabled", "context"), steps)}'
    )
    if failures:
        report += "<h2>Unreadable services</h2>" + table(("service", "error"), failures.items())
    path.write_text(report + "</html>\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packages", type=Path, required=True, help="Integration Server packages directory")
    parser.add_argument("--service", required=True, help="Parent Flow name, for example demo.api:get")
    parser.add_argument("--out", type=Path, required=True, help="Report output directory")
    args = parser.parse_args()
    try:
        calls, mappings, steps, failures = analyze(args.service, args.packages)
        args.out.mkdir(parents=True, exist_ok=True)
        write_csv(args.out / "calls.csv", CALL_COLUMNS, calls)
        write_csv(args.out / "mapping.csv", MAP_COLUMNS, mappings)
        write_html(args.out / "index.html", args.service, calls, steps, failures)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"Error: {exc}\n")
    print(f"{args.out / 'index.html'} | {len(calls)} call sites | {len(mappings)} mappings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
