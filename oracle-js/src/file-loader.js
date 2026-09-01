/**
 * A Vega loader that resolves a relative path against several roots in turn.
 *
 * Specifications name their data relatively — `data/barley.json` means "beside me" — but "beside me"
 * is not one place. A fixture in `test-fixtures/specs/` keeps its data one level up in
 * `test-fixtures/data/`, so the whole corpus shares it; an example copied out of the Vega gallery
 * keeps it in a `data/` directory of its own, right next to the spec. Both are ordinary, so both are
 * tried, nearest first.
 *
 * This is the same arrangement `FallbackDataLoader` gives the Kotlin side, and it has to be: the two
 * engines have to read the same bytes or the comparison between them means nothing.
 *
 * **File mode only.** Nothing here reaches the network — a reference has to be reproducible from a
 * checked-out tree with no connection (CONTRIBUTING.md). Data that is missing is fetched by
 * `scripts/oracle.sh` as a deliberate, reviewable step, never as a side effect of rendering.
 */

import * as vega from 'vega';

export function fileLoader(roots) {
  const loaders = roots.map((root) => vega.loader({ mode: 'file', baseURL: root }));
  const primary = loaders[0];

  return {
    ...primary,
    sanitize: (uri, options) => primary.sanitize(uri, options),
    http: (url, options) => primary.http(url, options),
    file: (filename) => primary.file(filename),
    async load(uri, options) {
      let refusal;
      for (const loader of loaders) {
        try {
          return await loader.load(uri, options);
        } catch (error) {
          refusal = error;
        }
      }
      throw refusal;
    },
  };
}

/** The roots a fixture's data may live under: its own directory, then the corpus root above it. */
export function rootsFor(specPath) {
  const directory = specPath.replace(/\/[^/]*$/, '') || '.';
  const parent = directory.replace(/\/[^/]*$/, '') || '.';
  return directory === parent ? [directory] : [directory, parent];
}
