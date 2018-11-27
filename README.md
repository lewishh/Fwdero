# Fwdero CorDapp
## Pre-Requisites

You will need the following installed on your machine before you can start:

* [JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) 
  installed and available on your path (Minimum version: 1.8_131).
* [IntelliJ IDEA](https://www.jetbrains.com/idea/download/) (Minimum version 2017.1)
* git
* Optional: [h2 web console](http://www.h2database.com/html/download.html)
  (download the "platform-independent zip")

For more detailed information, see the
[getting set up](https://docs.corda.net/getting-set-up.html) page on the
Corda docsite.

For IDE, compilation and JVM version issues, see the
[Troubleshooting](https://docs.corda.net/troubleshooting.html) page on the Corda docsite.

## Getting Set Up

To get started, clone this repository with:

     git clone https://github.com/lewishh/Fwdero.git

The CorDapp is split into two modules with most functionality under the cordapp directory. The states and contracts are stored in the cordapp-contracts-state directory.

## Building the CorDapp template:

**Unix:** 

     ./gradlew deployNodes

**Windows:**

     gradlew.bat deployNodes

Note: You'll need to re-run this build step after making any changes to
the template for these to take effect on the node.

## Running the Nodes

Once the build finishes, change directories to the folder where the newly
built nodes are located:

     cd build/nodes

The Gradle build script will have created a folder for each node. You'll
see three folders, one for each node and a `runnodes` script. You can
run the nodes with:

**Unix:**

     ./runnodes

**Windows:**

    runnodes.bat

This produces a Party A, Party B, Oracle and Notary.

When the nodes have booted up, you should see a message like the following 
in the console: 

     Node started up and registered in 5.007 sec

## Interacting with the CorDapp via HTTP

The CorDapp defines a couple of HTTP API end-points and also serves some
static web content. Initially, these return generic template responses.

The nodes can be found using the following port numbers, defined in 
`build.gradle`, as well as the `node.conf` file for each node found
under `build/nodes/partyX`:

     PartyA: localhost:10007
     PartyB: localhost:10010

As the nodes start up, they should tell you which host and port their
embedded web server is running on. The API endpoints served are:

     /api/forward/

And the static web content is served from:

     /web/forward/
