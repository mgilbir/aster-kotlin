import { compile } from 'vega-lite';
import fs from 'fs';
const out = compile(JSON.parse(fs.readFileSync(process.argv[2],'utf8'))).spec;
(out.axes||[]).forEach(a=>console.log(a.scale, a.grid, JSON.stringify(a.title)));
