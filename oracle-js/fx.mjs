import { functionContext } from 'vega-functions';
import * as vegaExpr from 'vega-expression';
const names = new Set(Object.keys(functionContext));
// plus the codegen's whitelisted globals
const mod = await import('vega-expression/src/functions.js').catch(()=>null);
console.log(JSON.stringify([...names].sort()));
