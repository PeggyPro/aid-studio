import { assertNodeVersion } from './node-runtime.mjs'

try {
  assertNodeVersion()
} catch (error) {
  console.error(error.message)
  process.exit(1)
}
