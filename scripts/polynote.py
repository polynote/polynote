#!/usr/bin/env python3
import sys
from pathlib import Path
import os
import shlex

if not os.environ.get('PYTHONPATH'):
    os.environ['PYTHONPATH'] = sys.prefix

polynote_dir = Path(__file__).resolve().parent
polynote_dir.chdir()

paths = [ Path(p) for p in sys.path if Path(p).exists() ]
jep_paths = [ p.joinpath("jep") for p in paths if p.joinpath("jep").exists() ]

if len(jep_paths) >= 1:
    jep_path = jep_paths[0]
else:
    raise Exception("Couldn't find jep library. Try running `pip3 install jep` first.")

plugins_path = polynote_dir / "plugins.d"
plugins = []

if plugins_path.exists():
    plugins = list(plugins_path.glob("*.jar"))

deps_path = polynote_dir / "deps"

if not(deps_path.exists()):
    raise Exception("Couldn't find the deps directory. Are we in the polynote installation directory?")

deps = deps_path.glob("*.jar")
classpath = "polynote.jar:" + ":".join([":".join([ f'"{d}"' for d in deps ]), ":".join([ f'"{p}"' for p in plugins ])])
cmd = f"java -cp polynote.jar:{classpath} -Djava.library.path={jep_path} polynote.Main {' '.join(sys.argv[1:])}"
cmd = shlex.split(cmd)
print(cmd)
os.execvp(cmd[0], cmd)
