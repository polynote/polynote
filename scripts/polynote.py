#!/usr/bin/env python3
import sys
from pathlib import Path
import os
import shlex

# Depending on how Python is installed, sometimes it's hard to find jep or other important libraries (e.g., libpython). 
# The sys module contains a bunch of *_prefix attributes that point to various locations where these libraries might be, 
# such as https://docs.python.org/3/library/sys.html#sys.exec_prefix 
# We set both the PYTHONPATH and the LD_LIBRARY_PATH just in case
sys_prefixes = {getattr(sys, sys_prefix) for sys_prefix in filter(lambda name: "prefix" in name, dir(sys))}
sys_prefixes = filter(lambda x: x is not None, sys_prefixes)
if not os.environ.get('PYTHONPATH'):
    os.environ['PYTHONPATH'] = os.pathsep.join(sys_prefixes)
else: 
    print("Using user-provided PYTHONPATH")
    
if not os.environ.get('LD_LIBRARY_PATH'):
    os.environ['LD_LIBRARY_PATH'] = os.pathsep.join([os.path.join(path, "lib") for path in sys_prefixes])
else: 
    print("Using user-provided LD_LIBRARY_PATH")

scala_version = os.environ.get('POLYNOTE_SCALA_VERSION', '2.12')

polynote_dir = os.path.dirname(os.path.realpath(__file__))
os.chdir(polynote_dir)

paths = [ Path(p) for p in sys.path if Path(p).exists() ]
jep_paths = [ p.joinpath("jep") for p in paths if p.joinpath("jep").exists() ]

if len(jep_paths) >= 1:
    jep_path = jep_paths[0]
else:
    raise Exception("Couldn't find jep library. Try running `pip3 install jep` first.")

plugins_path = Path(polynote_dir).joinpath("plugins.d", scala_version)
plugins = []

if plugins_path.exists():
    plugins = list(plugins_path.glob("*.jar"))

deps_path = Path(polynote_dir).joinpath("deps", scala_version)

if not(deps_path.exists()):
    raise Exception("Couldn't find the deps directory. Are we in the polynote installation directory?")

deps = Path(polynote_dir).joinpath("deps", scala_version).glob("*.jar")
classpath = ":".join([":".join([ f'"{d}"' for d in deps ]), ":".join([ f'"{p}"' for p in plugins ])])
cmd = f"java -cp {classpath} -Djava.library.path={jep_path} polynote.Main {' '.join(sys.argv[1:])}"
cmd = shlex.split(cmd)
print(cmd)
os.execvp(cmd[0], cmd)
