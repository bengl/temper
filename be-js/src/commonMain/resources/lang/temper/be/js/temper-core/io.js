import { empty } from "./core.js";

/**
 * @param {number} ms
 * @returns {Promise<Empty>}
 */
export function stdSleep(ms) {
  return new Promise(resolve => setTimeout(() => resolve(empty()), ms));
}

/**
 * @returns {Promise<string | null>}
 */
/** @returns {number} */
export function stdTermCols() {
  if (typeof process !== 'undefined' && process.stdout && process.stdout.columns) {
    return process.stdout.columns;
  }
  return 80;
}

/** @returns {number} */
export function stdTermRows() {
  if (typeof process !== 'undefined' && process.stdout && process.stdout.rows) {
    return process.stdout.rows;
  }
  return 24;
}

export function stdReadLine() {
  return new Promise(resolve => {
    if (typeof process !== 'undefined' && process.stdin) {
      process.stdin.resume();
      process.stdin.setEncoding('utf8');
      if (process.stdin.isTTY && process.stdin.setRawMode) {
        process.stdin.setRawMode(true);
      }
      process.stdin.once('data', data => {
        const str = data.toString();
        // Ctrl+C in raw mode
        if (str === '\x03') {
          process.exit();
        }
        resolve(str.trim());
      });
    } else {
      resolve(null);
    }
  });
}
