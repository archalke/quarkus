package io.quarkus.vertx.http.deployment;

import static io.quarkus.arc.processor.DotNames.APPLICATION_SCOPED;
import static io.quarkus.arc.processor.DotNames.SINGLETON;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;

import org.jboss.jandex.MethodInfo;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.security.spi.runtime.MethodDescription;
import io.quarkus.vertx.http.runtime.HttpBuildTimeConfig;
import io.quarkus.vertx.http.runtime.management.ManagementInterfaceBuildTimeConfig;
import io.quarkus.vertx.http.runtime.security.BasicAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.EagerSecurityInterceptorStorage;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticator;
import io.quarkus.vertx.http.runtime.security.HttpAuthorizer;
import io.quarkus.vertx.http.runtime.security.HttpSecurityRecorder;
import io.quarkus.vertx.http.runtime.security.MtlsAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.PathMatchingHttpSecurityPolicy;
import io.quarkus.vertx.http.runtime.security.VertxBlockingSecurityExecutor;
import io.vertx.core.http.ClientAuth;
import io.vertx.ext.web.RoutingContext;

public class HttpSecurityProcessor {

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void produceNamedHttpSecurityPolicies(List<HttpSecurityPolicyBuildItem> httpSecurityPolicyBuildItems,
            HttpSecurityRecorder recorder) {
        if (!httpSecurityPolicyBuildItems.isEmpty()) {
            recorder.setBuildTimeNamedPolicies(httpSecurityPolicyBuildItems.stream().collect(
                    Collectors.toMap(HttpSecurityPolicyBuildItem::getName, HttpSecurityPolicyBuildItem::getPolicySupplier)));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    AdditionalBeanBuildItem initFormAuth(
            HttpSecurityRecorder recorder,
            HttpBuildTimeConfig buildTimeConfig,
            BuildProducer<RouteBuildItem> filterBuildItemBuildProducer) {
        if (buildTimeConfig.auth.form.enabled) {
            if (!buildTimeConfig.auth.proactive) {
                filterBuildItemBuildProducer.produce(RouteBuildItem.builder().route(buildTimeConfig.auth.form.postLocation)
                        .handler(recorder.formAuthPostHandler()).build());
            }
            return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(FormAuthenticationMechanism.class)
                    .setDefaultScope(SINGLETON).build();
        }
        return null;
    }

    @BuildStep
    AdditionalBeanBuildItem initMtlsClientAuth(HttpBuildTimeConfig buildTimeConfig) {
        if (isMtlsClientAuthenticationEnabled(buildTimeConfig)) {
            return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(MtlsAuthenticationMechanism.class)
                    .setDefaultScope(SINGLETON).build();
        }
        return null;
    }

    @BuildStep(onlyIf = IsApplicationBasicAuthRequired.class)
    @Record(ExecutionTime.STATIC_INIT)
    SyntheticBeanBuildItem initBasicAuth(
            HttpSecurityRecorder recorder,
            HttpBuildTimeConfig buildTimeConfig,
            BuildProducer<SecurityInformationBuildItem> securityInformationProducer) {
        SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                .configure(BasicAuthenticationMechanism.class)
                .types(HttpAuthenticationMechanism.class)
                .scope(Singleton.class)
                .supplier(recorder.setupBasicAuth(buildTimeConfig));
        if (!buildTimeConfig.auth.form.enabled && !isMtlsClientAuthenticationEnabled(buildTimeConfig)
                && !buildTimeConfig.auth.basic.orElse(false)) {
            //if not explicitly enabled we make this a default bean, so it is the fallback if nothing else is defined
            configurator.defaultBean();
            if (buildTimeConfig.auth.basic.isPresent() && buildTimeConfig.auth.basic.get()) {
                securityInformationProducer.produce(SecurityInformationBuildItem.BASIC());
            }
        }

        return configurator.done();
    }

    public static boolean applicationBasicAuthRequired(HttpBuildTimeConfig buildTimeConfig,
            ManagementInterfaceBuildTimeConfig managementInterfaceBuildTimeConfig) {
        //basic auth explicitly disabled
        if (buildTimeConfig.auth.basic.isPresent() && !buildTimeConfig.auth.basic.get()) {
            return false;
        }
        if (!buildTimeConfig.auth.basic.orElse(false)) {
            if ((buildTimeConfig.auth.form.enabled || isMtlsClientAuthenticationEnabled(buildTimeConfig))
                    || managementInterfaceBuildTimeConfig.auth.basic.orElse(false)) {
                //if form auth is enabled and we are not then we don't install
                return false;
            }
        }

        return true;
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void setupAuthenticationMechanisms(
            HttpSecurityRecorder recorder,
            BuildProducer<FilterBuildItem> filterBuildItemBuildProducer,
            BuildProducer<AdditionalBeanBuildItem> beanProducer,
            Capabilities capabilities,
            HttpBuildTimeConfig buildTimeConfig,
            BuildProducer<SecurityInformationBuildItem> securityInformationProducer) {
        if (!buildTimeConfig.auth.form.enabled && buildTimeConfig.auth.basic.orElse(false)) {
            securityInformationProducer.produce(SecurityInformationBuildItem.BASIC());
        }

        if (capabilities.isPresent(Capability.SECURITY)) {
            beanProducer
                    .produce(AdditionalBeanBuildItem.builder().setUnremovable()
                            .addBeanClass(VertxBlockingSecurityExecutor.class).setDefaultScope(APPLICATION_SCOPED).build());
            beanProducer
                    .produce(AdditionalBeanBuildItem.builder().setUnremovable().addBeanClass(HttpAuthenticator.class)
                            .addBeanClass(HttpAuthorizer.class).build());
            beanProducer.produce(AdditionalBeanBuildItem.unremovableOf(PathMatchingHttpSecurityPolicy.class));
            filterBuildItemBuildProducer
                    .produce(new FilterBuildItem(
                            recorder.authenticationMechanismHandler(buildTimeConfig.auth.proactive),
                            FilterBuildItem.AUTHENTICATION));
            filterBuildItemBuildProducer
                    .produce(new FilterBuildItem(recorder.permissionCheckHandler(), FilterBuildItem.AUTHORIZATION));
        }
    }

    @BuildStep
    void collectEagerSecurityInterceptors(List<EagerSecurityInterceptorCandidateBuildItem> interceptorCandidates,
            HttpBuildTimeConfig buildTimeConfig, Capabilities capabilities,
            BuildProducer<EagerSecurityInterceptorBuildItem> interceptorsProducer) {
        if (!buildTimeConfig.auth.proactive && capabilities.isPresent(Capability.SECURITY)
                && !interceptorCandidates.isEmpty()) {
            List<MethodInfo> allInterceptedMethodInfos = interceptorCandidates
                    .stream()
                    .map(EagerSecurityInterceptorCandidateBuildItem::getMethodInfo)
                    .collect(Collectors.toList());
            Map<RuntimeValue<MethodDescription>, Consumer<RoutingContext>> methodToInterceptor = interceptorCandidates
                    .stream()
                    .collect(Collectors.toMap(EagerSecurityInterceptorCandidateBuildItem::getDescriptionRuntimeValue,
                            EagerSecurityInterceptorCandidateBuildItem::getSecurityInterceptor));
            interceptorsProducer.produce(new EagerSecurityInterceptorBuildItem(allInterceptedMethodInfos, methodToInterceptor));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void produceEagerSecurityInterceptorStorage(HttpSecurityRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> producer,
            Optional<EagerSecurityInterceptorBuildItem> interceptors) {
        if (interceptors.isPresent()) {
            producer.produce(SyntheticBeanBuildItem
                    .configure(EagerSecurityInterceptorStorage.class)
                    .scope(ApplicationScoped.class)
                    .supplier(
                            recorder.createSecurityInterceptorStorage(interceptors.get().methodCandidateToSecurityInterceptor))
                    .unremovable()
                    .done());
        }
    }

    private static boolean isMtlsClientAuthenticationEnabled(HttpBuildTimeConfig buildTimeConfig) {
        return !ClientAuth.NONE.equals(buildTimeConfig.tlsClientAuth);
    }

    static class IsApplicationBasicAuthRequired implements BooleanSupplier {
        private final boolean required;

        public IsApplicationBasicAuthRequired(HttpBuildTimeConfig httpBuildTimeConfig,
                ManagementInterfaceBuildTimeConfig managementInterfaceBuildTimeConfig) {
            required = applicationBasicAuthRequired(httpBuildTimeConfig, managementInterfaceBuildTimeConfig);
        }

        @Override
        public boolean getAsBoolean() {
            return required;
        }
    }
}
