/*
 * License header that the parser removes.
 */

package org.springframework.boot.docs.testing.testcontainers.serviceconnections;

@SpringBootTest
@Testcontainers
class MyIntegrationTests {

	@Container
	@ServiceConnection
	static Neo4jContainer neo4j = new Neo4jContainer("neo4j:5");

}
