package com.aid.common.storage;

import com.aid.common.aid.core.service.ConfigService;
import java.util.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration(proxyBeanMethods=false)
public class LocalStorageConfiguration {
    @Bean(initMethod="initialize")
    public LocalStorageBootstrap localStorageRootBootstrap(ConfigService configs,ConfigurableEnvironment environment,List<LocalStorageAreaProvider> providers){
        return new LocalStorageBootstrap(configs,environment,providers);
    }
    @Bean
    public static BeanFactoryPostProcessor localStorageInitializationOrder(){
        return factory->{
            Set<String> consumers=new LinkedHashSet<>();
            for(String provider:factory.getBeanNamesForType(LocalStorageAreaProvider.class,false,false)){
                LocalStorageAreaProvider areas=factory.getBean(provider,LocalStorageAreaProvider.class);
                consumers.addAll(areas.dependentBeans());
            }
            // Property objects can be bound before the WebServer factory itself is instantiated.
            // They must observe the managed paths as well as the consuming factory.
            for(Class<?> type:List.of(org.springframework.boot.autoconfigure.web.ServerProperties.class,
                    org.springframework.boot.autoconfigure.web.servlet.MultipartProperties.class))
                consumers.addAll(Arrays.asList(factory.getBeanNamesForType(type,false,false)));
            for(String name:consumers)if(factory.containsBeanDefinition(name)){
                    var definition=factory.getBeanDefinition(name);Set<String> depends=new LinkedHashSet<>();
                    if(definition.getDependsOn()!=null)depends.addAll(Arrays.asList(definition.getDependsOn()));
                    depends.add("localStorageRootBootstrap");definition.setDependsOn(depends.toArray(String[]::new));
            }
        };
    }
}
