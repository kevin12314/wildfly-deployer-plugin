WildFly Deployer Plugin
=====================

Jenkins plugin that deploys applications (WAR or EAR files) to a WildFly or JBoss EAP server or server group.

Read more: [WildFly Deployer Plugin](https://wiki.jenkins-ci.org/display/JENKINS/WildFly+Deployer+Plugin)


Development Configuration
--------------

Run...

	mvn clean package

...to create the plugin .hpi file, download dependencies, etc.

Run... 

	mvn hpi:run

...to start the Jenkins instance and to create the target server directories. 

Exit the server.  Run...

	mvn exec:exec@cp exec:exec@rm hpi:run

(The goals above require Maven v3.3.3 or later.)

Now test...
 
Installing to a Jenkins Server
--------------

The Jenkins WAR file must be re-assembled (or the expanded WAR file modified) with the following changes:

1.  Copy dependency wildfly-cli-8.2.1.Final.jar to <jenkins_war_root>/WEB-INF/lib.
2.  Copy dependency log4j-over-slf4j-1.7.7.jar to <jenkins_war_root>/WEB-INF/lib.  (Not required if using Jenkins version 1.622 or later.)

(Note: this is not ideal, but is required due to the crazy dynamic classloading behaviour in the WildFly libraries.)  

Copy the ./target/wildfly-deployer.hpi file to the $JENKINS_HOME/plugins directory, or use the plugin console to upload the HPI file. 

Restart Jenkins.

Now test...

License
-------

The MIT License
Copyright (c) 2015, Dan B. Dillingham  
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER

