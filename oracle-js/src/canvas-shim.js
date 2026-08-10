/**
 * Just enough `<canvas>` for upstream's `heatmap` to run without a browser or a native library.
 *
 * `vega-canvas` looks for `document.createElement('canvas')` first and a native `canvas` module
 * second, and finds neither under plain Node — so `Heatmap.transform` throws on `getContext` and the
 * two examples built on it have no reference at all.
 *
 * This is a faithful oracle for that transform and **only** for it. `toCanvas` does nothing but
 * allocate an image buffer, write RGBA bytes into it and hand it back; it never draws a shape, never
 * measures text and never composites. Every byte it produces is arithmetic on the grid, so a buffer
 * with no drawing behind it gives exactly the pixels a browser would. Anything that actually
 * rasterises vector graphics would need a real canvas and would be silently wrong here — so this
 * refuses the calls it cannot honour rather than returning something plausible.
 */

class ShimImageData {
  constructor(width, height) {
    this.width = width;
    this.height = height;
    this.data = new Uint8ClampedArray(width * height * 4);
  }
}

class ShimContext {
  constructor(canvas) {
    this.canvas = canvas;
    this._pixels = new Uint8ClampedArray(canvas.width * canvas.height * 4);
  }

  getImageData(x, y, width, height) {
    const image = new ShimImageData(width, height);
    for (let row = 0; row < height; ++row) {
      const from = ((y + row) * this.canvas.width + x) * 4;
      image.data.set(this._pixels.subarray(from, from + width * 4), row * width * 4);
    }
    return image;
  }

  putImageData(image, x, y) {
    for (let row = 0; row < image.height; ++row) {
      const to = ((y + row) * this.canvas.width + x) * 4;
      this._pixels.set(
        image.data.subarray(row * image.width * 4, (row + 1) * image.width * 4),
        to
      );
    }
  }
}

const REFUSED = [
  'fillRect', 'clearRect', 'strokeRect', 'beginPath', 'fill', 'stroke', 'drawImage',
  'measureText', 'fillText', 'strokeText', 'arc', 'moveTo', 'lineTo', 'closePath', 'save',
  'restore', 'translate', 'scale', 'rotate', 'setTransform', 'clip', 'createLinearGradient',
];

for (const name of REFUSED) {
  ShimContext.prototype[name] = function () {
    throw new Error(
      `oracle-js/src/canvas-shim.js cannot ${name}: it is an image buffer, not a renderer. ` +
        'Something now needs a real canvas — install `canvas` rather than extending this.'
    );
  };
}

class ShimCanvas {
  constructor(width, height) {
    this.width = width;
    this.height = height;
    this._context = null;
  }

  getContext(type) {
    if (type !== '2d') return null;
    return this._context || (this._context = new ShimContext(this));
  }
}

/** Installs the shim as a global `document`, if there is not already one. */
export function installCanvasShim() {
  if (typeof globalThis.document !== 'undefined') return;
  globalThis.document = {
    createElement(tag) {
      if (tag !== 'canvas') {
        throw new Error(`oracle-js/src/canvas-shim.js only makes a canvas, not a <${tag}>`);
      }
      return new ShimCanvas(0, 0);
    },
  };
}
