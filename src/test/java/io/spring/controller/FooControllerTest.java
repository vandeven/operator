package io.spring.controller;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.ApiextensionsV1Api;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1CustomResourceDefinition;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.Yaml;
import io.spring.controller.models.V1Foo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.FileCopyUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
@ContextConfiguration(initializers = FooControllerTest.TestcontainersInitializer.class)
public class FooControllerTest {

	@Container
	static K3sContainer k3sContainer = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.24.12-k3s1"));

	/**
	 * This gets loaded after testcontainer startup and before application startup
	 * This is used to create a custom ApiClient bean with configuration from the test container.
	 * It also creates the CRD so the application doesn't throw an immediate error
	 */
	static class TestcontainersInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext ctx) {
			try {
				ApiClient client = createClient();
				// The kubernetes client creates a bean of type ApiClient if no other bean of that type is available
				// We create one here using configuration from the testcontainer
				ctx.getBeanFactory().registerSingleton("defaultApiClient", client);
				createCRD(client);
			} catch (IOException | ApiException e) {
				throw new RuntimeException(e);
			}
		}

		private static ApiClient createClient() throws IOException {
			KubeConfig config = KubeConfig.loadKubeConfig(new StringReader(k3sContainer.getKubeConfigYaml()));
			ApiClient client = ClientBuilder.kubeconfig(config).build();
			return client;
		}

		private static void createCRD(ApiClient client) throws IOException, ApiException {
			var crdyaml = FileCopyUtils.copyToString(new InputStreamReader(new ClassPathResource(
							"foo-crd.yaml").getInputStream()));
			var crd = Yaml.loadAs(crdyaml, V1CustomResourceDefinition.class);
			new ApiextensionsV1Api(client).createCustomResourceDefinition(crd, null, null,null,null);
		}
	}

	@Autowired
	private ApiClient defaultApiClient;

	@Test
	public void testResourcesCreated() throws IOException, ApiException {
		CustomObjectsApi customObjectsApi = new CustomObjectsApi(defaultApiClient);
		AppsV1Api appsV1Api = new AppsV1Api(defaultApiClient);
		CoreV1Api coreV1Api = new CoreV1Api(defaultApiClient);
		var foo = Yaml.loadAs(FileCopyUtils.copyToString(new InputStreamReader(new ClassPathResource(
						"my-first-foo.yaml").getInputStream())), V1Foo.class);
		customObjectsApi.createNamespacedCustomObject("spring.io", "v1", "default", "foos", foo, null, null, null);
		Awaitility.await()
						.atMost(Duration.of(10, ChronoUnit.SECONDS))
						.until(() -> deploymentExists(appsV1Api, "my-first-foo"));
		assertTrue(configMapExists(coreV1Api, "my-first-foo"));
		assertTrue(deploymentExists(appsV1Api, "my-first-foo"));
	}

	private boolean deploymentExists(AppsV1Api appsV1Api, String name) throws ApiException {
		var deploymentList = appsV1Api.listNamespacedDeployment("default", null, null, null, null, null, null, null, null, null, null, null);
		return deploymentList.getItems().stream().anyMatch(item -> item.getMetadata().getName().equals(name));
	}

	private boolean configMapExists(CoreV1Api coreV1Api, String name) throws ApiException {
		var configMapList = coreV1Api.listNamespacedConfigMap("default",null, null, null, null,null,null,null, null, null, null,null);
		return configMapList.getItems().stream().anyMatch(item -> item.getMetadata().getName().equals(name));
	}

}
