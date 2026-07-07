"""Live view listener for suzanne (Clojure -> Blender modeling).

Runs inside the Blender GUI. Usually launched for you by
(suzanne.core/start-live-viewer!), or manually:

    blender --python live_addon.py

Listens on localhost for one-line JSON commands from the Clojure REPL:

    {"cmd": "load", "path": "/tmp/suzanne-live.glb", "frame": true}
    {"cmd": "clear"}

Each "load" replaces the contents of the "suzanne-live" collection with the
given mesh file (.glb/.gltf/.stl/.obj). The viewport camera is left alone so
your orbit survives updates; "frame": true zooms to fit (sent on first load).

All commands execute on Blender's main thread via a bpy.app.timers poll, so
no locking is needed. Binds to 127.0.0.1 only.
"""

import bpy
import json
import os
import socket

HOST = "127.0.0.1"
PORT = int(os.environ.get("SUZANNE_LIVE_PORT", "4777"))
COLLECTION = "suzanne-live"
POLL_SECONDS = 0.2

_server = None


def _ensure_collection():
    coll = bpy.data.collections.get(COLLECTION)
    if not coll:
        coll = bpy.data.collections.new(COLLECTION)
        bpy.context.scene.collection.children.link(coll)
    return coll


def _clear(coll):
    for obj in list(coll.objects):
        bpy.data.objects.remove(obj, do_unlink=True)
    for block in (bpy.data.meshes, bpy.data.materials):
        for item in list(block):
            if item.users == 0:
                block.remove(item)


def _frame_view():
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            if area.type == 'VIEW_3D':
                region = next((r for r in area.regions if r.type == 'WINDOW'), None)
                if region:
                    with bpy.context.temp_override(window=window, area=area, region=region):
                        bpy.ops.view3d.view_all()
                return


def _load(path, frame):
    if not os.path.exists(path):
        return "error: no such file: %s" % path
    coll = _ensure_collection()
    _clear(coll)
    before = set(bpy.data.objects)
    ext = os.path.splitext(path)[1].lower()
    if ext in (".glb", ".gltf"):
        bpy.ops.import_scene.gltf(filepath=path)
    elif ext == ".stl":
        bpy.ops.wm.stl_import(filepath=path)
    elif ext == ".obj":
        bpy.ops.wm.obj_import(filepath=path)
    else:
        return "error: unsupported extension: %s" % ext
    new = [o for o in bpy.data.objects if o not in before]
    for obj in new:
        for c in list(obj.users_collection):
            c.objects.unlink(obj)
        coll.objects.link(obj)
    if frame:
        _frame_view()
    return "ok %d objects" % len(new)


def _handle(line):
    try:
        msg = json.loads(line)
        cmd = msg.get("cmd")
        if cmd == "load":
            return _load(msg["path"], bool(msg.get("frame")))
        if cmd == "clear":
            _clear(_ensure_collection())
            return "ok cleared"
        return "error: unknown cmd: %s" % cmd
    except Exception as exc:
        import traceback
        traceback.print_exc()
        return "error: %s" % exc


def _poll():
    while True:
        try:
            conn, _addr = _server.accept()
        except BlockingIOError:
            break
        except OSError:
            break
        try:
            conn.settimeout(2.0)
            data = b""
            while not data.endswith(b"\n"):
                chunk = conn.recv(65536)
                if not chunk:
                    break
                data += chunk
            reply = _handle(data.decode("utf-8"))
            conn.sendall((reply + "\n").encode("utf-8"))
        except Exception as exc:
            print("[suzanne live] connection error:", exc)
        finally:
            conn.close()
    return POLL_SECONDS


def start():
    global _server
    if _server is not None:
        print("[suzanne live] already running")
        return
    _server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    _server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    _server.bind((HOST, PORT))
    _server.listen(5)
    _server.setblocking(False)
    bpy.app.timers.register(_poll, persistent=True)
    print("[suzanne live] listening on %s:%d" % (HOST, PORT))


if __name__ == "__main__":
    start()
