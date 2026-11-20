# Blender headless: OBJ folder -> GLB (+ optional USDZ)
# blender -b --factory-startup --python convert_glb.py -- <objDir> <out.glb> [out.usdz]
import sys, os, glob
import bpy

argv = sys.argv[sys.argv.index("--") + 1:]
obj_dir, out_path = argv[0], argv[1]

objs = glob.glob(os.path.join(obj_dir, "*.obj"))
if not objs:
    print("NO_OBJ_FOUND")
    sys.exit(1)

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.wm.obj_import(filepath=objs[0])

for ob in bpy.context.scene.objects:
    if ob.type == "MESH":
        for poly in ob.data.polygons:
            poly.use_smooth = True

kw = dict(filepath=out_path, export_format="GLB", use_selection=False)
try:
    bpy.ops.export_scene.gltf(**kw, export_image_format="JPEG", export_jpeg_quality=90)
except TypeError:
    bpy.ops.export_scene.gltf(**kw)
print("GLB_OK", out_path)

if len(argv) > 2:
    try:
        bpy.ops.wm.usd_export(filepath=argv[2])
        print("USDZ_OK", argv[2])
    except Exception as e:
        print("USDZ_FAIL", e)
