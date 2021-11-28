#!/bin/bash

HASH=__HASH__

cd "__USERDIR__"  || exit

if ! hash ng 2>/dev/null ; then
    exec __JAVA__ -cp "__ENSIME_JAR__" -Dscala.classpath.closeZip=true ensime.Main __USER_SETTINGS__ $*
fi

DIR="/tmp/__USER__/ensime/$HASH/"

export NAILGUN_SERVER=127.0.0.1

if [ -f "$DIR/pid" ] ; then
    NAILGUN_PID=$(cat "$DIR/pid")
    if ! ps -p $NAILGUN_PID > /dev/null ; then
        rm -rf "$DIR"
        unset NAILGUN_PID
    fi
fi

if [ -z $NAILGUN_PID ] ; then
    if [ -z "$*" ] ; then exit 0 ; fi

    mkdir -p "$DIR" 2>/dev/null || true

    __JAVA__ -cp "__ENSIME_JAR__" -Dscala.classpath.closeZip=true com.facebook.nailgun.NGServer $NAILGUN_SERVER:0 > "$DIR/stdout" 2> "$DIR/stderr" & &> /dev/null

    NAILGUN_PID=$!
    echo $NAILGUN_PID > "$DIR/pid"
elif [ -z "$*" ] ; then
    kill $NAILGUN_PID
    exit 0
fi

export NAILGUN_PORT=""
set_port() {
    NAILGUN_PORT=$(head -1 "$DIR/stdout" | sed -n 's/.*, port \([0-9]*\)\..*/\1/p')
}

set_port
while [ -z $NAILGUN_PORT ] ; do
    sleep 0.1
    set_port
done

ng ensime.Main __USER_SETTINGS__ $*
