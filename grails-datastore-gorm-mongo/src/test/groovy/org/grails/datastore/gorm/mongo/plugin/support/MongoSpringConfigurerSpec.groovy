package org.grails.datastore.gorm.mongo.plugin.support

import spock.lang.Specification
import grails.spring.BeanBuilder
import org.codehaus.groovy.grails.plugins.DefaultGrailsPluginManager
import org.codehaus.groovy.grails.commons.DefaultGrailsApplication
import org.grails.datastore.mapping.mongo.config.MongoMappingContext
import grails.gorm.tests.Person
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler

/**
 */
class MongoSpringConfigurerSpec extends Specification{

    void "Test configure Mongo via Spring"() {
        when:"The spring configurer is used"
            def configurer = new MongoSpringConfigurer()
            def config = configurer.getConfiguration()
            def bb = new BeanBuilder()
            final binding = new Binding()
            def application = new DefaultGrailsApplication([Person] as Class[], Thread.currentThread().contextClassLoader)

            application.config.grails.mongo.databaseName = "test"
            final closureConfig = {
                '*'(reference: true)
            }
            application.config.grails.mongo.default.mapping = closureConfig
            application.initialise()
            application.registerArtefactHandler(new DomainClassArtefactHandler())
            binding.setVariable("application", application)
            binding.setVariable("manager", new DefaultGrailsPluginManager([] as Class[], application))
            bb.setBinding(binding)
            bb.beans {
                grailsApplication(DefaultGrailsApplication, [Person] as Class[], Thread.currentThread().contextClassLoader) { bean ->
                    bean.initMethod = "initialise"
                }
                pluginManager(DefaultGrailsPluginManager, [] as Class[], ref("grailsApplication"))
            }
            bb.beans config
            def ctx = bb.createApplicationContext()
            MongoMappingContext mappingContext = ctx.getBean("mongoMappingContext",MongoMappingContext)
            def entity = mappingContext?.getPersistentEntity(Person.name)

        then:"The application context is created"
            ctx != null
            ctx.containsBean("persistenceInterceptor")
            mappingContext.defaultMapping == closureConfig
            entity != null
            entity.getPropertyByName('pets').getMapping().mappedForm.reference == true

    }
}
