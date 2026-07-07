#!/usr/bin/env bash
# Creates the Python venv with the bpy (Blender as a Python module) wheel.
# bpy 4.5.x wheels require Python 3.11 (brew install python@3.11).
#
# Point suzanne at the result with SUZANNE_PYTHON=<repo>/bpy-venv/bin/python
# (or run your REPL from this directory, which is the default location).
set -euo pipefail
cd "$(dirname "$0")/.."

PYTHON="${PYTHON:-python3.11}"
VENV="${VENV:-bpy-venv}"
BPY_VERSION="${BPY_VERSION:-4.5.3}"

if [ ! -x "$VENV/bin/python" ]; then
  "$PYTHON" -m venv "$VENV"
fi

"$VENV/bin/pip" install --quiet "bpy==$BPY_VERSION"
"$VENV/bin/python" -c "import bpy; print('bpy', bpy.app.version_string, 'ready at $VENV')"
