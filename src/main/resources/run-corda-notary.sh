#!/bin/bash

# If variable not present use default values

: ${CORDA_HOME:=/opt/corda}
: ${JAVA_OPTIONS:=-Xmx512m}
: ${NETWORK_MAP? Need a value for the network-map}
: ${OUR_NAME? Need a value for the expected FQDN of this node}
: ${OUR_PORT? Need a value for the port to bind to}

export CORDA_HOME JAVA_OPTIONS
echo "compatibilityZoneURL=\"${NETWORK_MAP}\"" >> node.conf
echo "p2pAddress=\"${OUR_NAME}:${OUR_PORT}\"" >> node.conf


cd ${CORDA_HOME}
java $JAVA_OPTIONS -jar ${CORDA_HOME}/corda.jar 2>&1