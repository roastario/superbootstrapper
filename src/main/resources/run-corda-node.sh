#!/bin/bash

# If variable not present use default values

: ${CORDA_HOME:=/opt/corda}
: ${JAVA_OPTIONS:=-Xmx512m}
: ${NETWORK_MAP? Need a value for the network-map}
: ${X500? Need a value for the x500 name of this node}
: ${OUR_NAME? Need a value for the expected FQDN of this node}
: ${OUR_PORT? Need a value for the port to bind to}

export CORDA_HOME JAVA_OPTIONS

##step one, modify the node.config

sed -i "/myLegalName/d" node.conf

echo "compatibilityZoneURL=\"${NETWORK_MAP}\"" >> node.conf
echo "myLegalName=\"${X500}\"" >> node.conf
echo "p2pAddress=\"${OUR_NAME}:${OUR_PORT}\"" >> node.conf


cd ${CORDA_HOME}
#curl --header "Content-Type: application/octet-stream" --data-binary @node.conf http://${NETWORK_MAP}/build-dev-certs -o dev-certs.zip
#unzip dev-certs.zip

java $JAVA_OPTIONS -jar ${CORDA_HOME}/corda.jar 2>&1