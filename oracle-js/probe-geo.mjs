import { geoMercator, geoPath, geoEquirectangular, geoGraticule } from 'd3-geo';
const shapes = {
  triangle: {type:'Polygon',coordinates:[[[-10,-10],[10,-10],[0,20],[-10,-10]]]},
  dateline: {type:'Polygon',coordinates:[[[170,10],[-170,10],[-170,-10],[170,-10],[170,10]]]},
  line: {type:'LineString',coordinates:[[-120,45],[120,-45]]},
  point: {type:'Point',coordinates:[10,20]},
  big: {type:'Polygon',coordinates:[[[-100,60],[100,60],[100,-60],[-100,-60],[-100,60]]]}
};
const p1 = geoMercator().scale(150).translate([450,250]);
const p2 = geoMercator().scale(200).translate([400,300]).rotate([-40, 20, 10]).center([15, 5]);
const p3 = geoEquirectangular().scale(120).translate([400,250]);
for (const [name, proj] of [['mercator', p1], ['rotated', p2], ['equirect', p3]]) {
  const path = geoPath(proj);
  for (const k of Object.keys(shapes)) {
    console.log(`${name}|${k}|${path(shapes[k])}`);
  }
}
const g = geoGraticule();
const lines = g.lines();
console.log('GRATICULE_COUNT|' + lines.length);
console.log('GRATICULE_FIRST|' + JSON.stringify(lines[0]));
console.log('GRATICULE_LAST|' + JSON.stringify(lines[lines.length-1]));
console.log('GRATICULE_OUTLINE|' + JSON.stringify(g.outline()).slice(0,200));
