#!/bin/bash

set -eo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

SCALA_VERSIONS="${SCALA_VERSIONS:-3.2.0 2.13.9 2.12.17 2.11.12}"

JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 >/dev/null | grep 'java.home' | sed 's/.* = //')

cd tests/simple
sbt --client reload
cd "$SCRIPT_DIR"

for V in $SCALA_VERSIONS ; do
  cd $SCRIPT_DIR
  sbt --client ++${V}! assembly

  CACHE_DIR=$HOME/.cache/ensime

  cd tests/simple
  sbt --client ++${V}! "clean;updateClassifiers;compile"

  > assertions-$V.out

  test_ensime() {
      echo "TEST: $*"
      echo "> $*" >> assertions-$V.out
      $CACHE_DIR$PWD/$* | sed "s|${PWD}|PWD|g" | sed "s|${HOME}|HOME|g" | sed "s|${JAVA_HOME}|JAVA_HOME|g" >> assertions-$V.out
      echo "=========" >> assertions-$V.out
  }

  stop_ensime() {
      $CACHE_DIR$PWD/$1
  }

  # make sure there's not a stale instance
  stop_ensime src/main/scala/simple.scala

  # note that many of these tests rely on the exact java version, e.g.
  # completions of the java.lang. package or source line numbers of jump to
  # source. That can't really be avoided without reducing the test coverage.

  test_ensime src/main/scala/simple.scala type src/main/scala.interactive/simple1.scala 38
  test_ensime src/main/scala/simple.scala symbol src/main/scala.interactive/simple1.scala 38

  test_ensime src/main/scala/simple.scala type src/main/scala.interactive/simple1.scala 51
  test_ensime src/main/scala/simple.scala symbol src/main/scala.interactive/simple1.scala 51

  test_ensime src/main/scala/simple.scala symbol src/main/scala/simple.scala 302
  test_ensime src/main/scala/simple.scala symbol src/main/scala/simple.scala 388
  test_ensime src/main/scala/simple.scala symbol src/main/scala/simple.scala 421

  test_ensime src/main/scala/simple.scala complete src/main/scala.interactive/simple1.scala 107
  test_ensime src/main/scala/simple.scala complete src/main/scala.interactive/simple1.scala 33
  test_ensime src/main/scala/simple.scala complete src/main/scala.interactive/simple3.scala 165

  test_ensime src/main/scala/simple.scala search List
  test_ensime src/main/scala/simple.scala search String
  test_ensime src/main/scala/simple.scala search Foo
  test_ensime src/main/scala/simple.scala search Paths

  test_ensime src/main/scala/simple.scala fqn src/main/scala/simple.scala 108
  test_ensime src/main/scala/simple.scala fqn src/main/scala/simple.scala 130
  test_ensime src/main/scala/simple.scala fqn src/main/scala/simple.scala 186
  test_ensime src/main/scala/simple.scala fqn src/main/scala/simple.scala 233
  test_ensime src/main/scala/simple.scala fqn src/main/scala/simple.scala 454
  test_ensime src/main/scala/simple.scala binary src/main/scala/simple.scala 108
  test_ensime src/main/scala/simple.scala binary src/main/scala/simple.scala 130

  # jar/src lookups
  test_ensime src/main/scala/simple.scala source src/main/scala/simple.scala 120
  test_ensime src/main/scala/simple.scala source src/main/scala/simple.scala 108
  test_ensime src/main/scala/simple.scala source src/main/scala/simple.scala 130
  test_ensime src/main/scala/simple.scala source src/main/scala/simple.scala 81
  test_ensime src/main/scala/simple.scala source src/main/scala/simple.scala 75
  test_ensime src/main/scala/simple.scala source src/main/scala/simple.scala 454

  # local class target lookup
  test_ensime src/main/scala/simple.scala source src/main/scala/simple.scala 153

  # context related tests
  test_ensime src/main/scala/simple.scala complete src/main/scala.interactive/simple2.scala 106 src/main/scala.interactive/simplelib.scala
  test_ensime src/main/scala/simple.scala source src/main/scala.interactive/simple2.scala 104 src/main/scala.interactive/simplelib.scala

  # source in same file
  test_ensime src/main/scala/simple.scala source src/main/scala.interactive/simple3.scala 104

  stop_ensime src/main/scala/simple.scala

  sbt --client ++${V}! "clean"
  if [ ! -d $CACHE_DIR$PWD/target ] ; then
      echo "ERROR: reverse lookup was not cleared $CACHE_DIR$PWD/target"
      exit 1
  fi
done

cd "$SCRIPT_DIR"
cd tests/simple
sbt --client shutdown

