import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.otlp.metrics.export.enabled=false",
        "management.tracing.export.enabled=false",
        "management.logging.export.otlp.enabled=false"
    })
@Import(IntegrationTestContainers.class)
public abstract class BaseIntegrationTest {
}
