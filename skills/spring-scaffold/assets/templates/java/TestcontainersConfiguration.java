@TestConfiguration(proxyBeanMethods = false)
@Import(IntegrationTestContainers.class)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    LgtmStackContainer grafanaLgtmContainer() {
        return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:0.34.0"));
    }
}
