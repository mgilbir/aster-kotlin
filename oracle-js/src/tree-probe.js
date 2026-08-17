#!/usr/bin/env node
/**
 * Dumps a Vega scenegraph's tree shape.
 *
 * Used to establish what a construct actually produces before implementing it — the group and facet
 * work was written against this output rather than against the documentation.
 *
 * Usage: node src/tree-probe.js <spec.vg.json>
 */
import { readFileSync } from 'node:fs';
import * as vega from 'vega';

const spec = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const view = new vega.View(vega.parse(spec), { renderer: 'none' });
await view.runAsync();

const KEYS = ['x','y','x2','y2','width','height','fill','stroke','text','size','clip','strokeWidth','opacity','align','baseline','fontSize'];

function dump(node, depth) {
  const pad = '  '.repeat(depth);
  if (node.marktype !== undefined) {
    console.log(`${pad}marktype=${node.marktype} role=${node.role} name=${node.name} items=${(node.items||[]).length} bounds=${bounds(node.bounds)}`);
    for (const item of node.items || []) dump(item, depth + 1);
  } else {
    const fields = KEYS.filter((k) => node[k] !== undefined).map((k) => `${k}=${JSON.stringify(node[k])}`).join(' ');
    console.log(`${pad}item ${fields} bounds=${bounds(node.bounds)}${node.datum ? ' datum=' + JSON.stringify(node.datum).slice(0, 140) : ''}`);
    for (const child of node.items || []) dump(child, depth + 1);
  }
}
function bounds(b) {
  return b ? `[${b.x1},${b.y1},${b.x2},${b.y2}]` : 'none';
}

dump(view.scenegraph().root, 0);
await view.finalize();
