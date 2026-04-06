import zipfile, os, sys

repo = os.path.join("build", "repo")
out = os.path.join("build", "bundle.zip")

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(repo):
        for f in files:
            full = os.path.join(root, f)
            arcname = os.path.relpath(full, repo).replace(os.sep, "/")
            zf.write(full, arcname)

print(f"Created {out} with {zf.namelist().__len__()} files" if False else f"Created {out}")
