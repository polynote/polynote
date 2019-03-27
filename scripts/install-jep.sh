#!/usr/bin/env bash

pip3 install jep ipython nbconvert jedi
export LD_LIBRARY_PATH=`pip3 show jep |grep "^Location:" |cut -d ':' -f 2 |cut -d ' ' -f 2`/jep:${LD_LIBRARY_PATH}
echo ${LD_LIBRARY_PATH}
