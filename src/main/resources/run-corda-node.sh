#!/bin/bash

: ${CORDA_HOME:=/opt/corda}
: ${JAVA_OPTIONS:=-Xmx512m}
: ${X500? Need a value for the x500 name of this node}
: ${OUR_NAME? Need a value for the expected FQDN of this node}
: ${OUR_PORT? Need a value for the port to bind to}

export CORDA_HOME JAVA_OPTIONS

sed -i "/myLegalName/d" node.conf
echo "myLegalName=\"${X500}\"" >> node.conf
echo "p2pAddress=\"${OUR_NAME}:${OUR_PORT}\"" >> node.conf

cp additional-node-infos/network-params/network-parameters .

bash node_info_watcher.sh &

cd ${CORDA_HOME}
java $JAVA_OPTIONS -jar ${CORDA_HOME}/corda.jar 2>&1