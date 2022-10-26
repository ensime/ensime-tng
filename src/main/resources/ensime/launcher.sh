#!/bin/bash

HASH=__HASH__
TMPDIR="__TMPDIR__/__HASH__/"

cd "__USERDIR__"  || exit

if ! hash ng 2>/dev/null ; then
    exec __JAVA__ -cp "__ENSIME_JAR__" -Dscala.classpath.closeZip=true ensime.Main __USER_SETTINGS__ $*
fi

export NAILGUN_SERVER=127.0.0.1

if [ -f "$TMPDIR/pid" ] ; then
    NAILGUN_PID=$(cat "$TMPDIR/pid")
    if ! ps -p $NAILGUN_PID > /dev/null ; then
        rm -rf "$TMPDIR"
        unset NAILGUN_PID
    fi
fi

if [ -z $NAILGUN_PID ] ; then
    if [ -z "$*" ] ; then exit 0 ; fi

    mkdir -p "$TMPDIR" 2>/dev/null || true

    __JAVA__ -cp "__ENSIME_JAR__" -Dscala.classpath.closeZip=true com.facebook.nailgun.NGServer $NAILGUN_SERVER:0 > "$TMPDIR/stdout" 2> "$TMPDIR/stderr" & &> /dev/null

    NAILGUN_PID=$!
    echo $NAILGUN_PID > "$TMPDIR/pid"
elif [ -z "$*" ] ; then
    kill $NAILGUN_PID
    exit 0
fi

export NAILGUN_PORT=""
set_port() {
    NAILGUN_PORT=$(head -1 "$TMPDIR/stdout" | sed -n 's/.*, port \([0-9]*\)\..*/\1/p')
}

set_port
while [ -z $NAILGUN_PORT ] ; do
    sleep 0.1
    set_port
done

ng ensime.Main __USER_SETTINGS__ $*

RESP=$?
if [ $RESP = "230" ] ; then
    echo "NAILGUN_CONNECT_FAILED, wiping $TMPDIR" 1>&2
    rm -rf "$TMPDIR"
fi
exit $RESP
