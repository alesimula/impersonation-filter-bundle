package features.impersonation

import framework.ReposeValveTest
import framework.mocks.MockIdentityService
import org.joda.time.DateTime
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.Endpoint
import org.rackspace.deproxy.Response
import spock.lang.Unroll

/**
 * Created by dimi5963 on 8/11/15.
 */
class ImpersonationWithCacheOffTest extends ReposeValveTest {
    static Endpoint identityEndpoint

    def static MockIdentityService fakeIdentityService

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)

        fakeIdentityService = new MockIdentityService(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(
                properties.identityPort, 'identity service', null, fakeIdentityService.handler)

        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/impersonation/cacheoff", params)
        repose.start()
        repose.waitForNon500FromUrl(reposeEndpoint)
    }

    def setup() {
        sleep 500
        fakeIdentityService.resetHandlers()
    }

    def cleanupSpec() {
        deproxy.shutdown()
        repose.stop()
    }

    def cleanup() {
        deproxy._removeEndpoint(identityEndpoint)
    }

    @Unroll("Impersonation scenarios - request: #requestMethod #requestURI -d #requestBody will return #responseCode with #responseMessage")
    def "When running with impersonation requests"() {
        given: "set up identity response"
        def impersonatedToken = UUID.randomUUID().toString()
        fakeIdentityService.with {
            client_tenant = reqTenant
            client_userid = reqTenant
            client_token = UUID.randomUUID().toString()
            tokenExpiresAt = DateTime.now().plusDays(1)
            impersonated_token = impersonatedToken
        }

        //impersonation request
        if(validateResponseCode > 200) {
            fakeIdentityService.impersonateTokenHandler = {
                request, xml ->
                    new Response(validateResponseCode, null, null, validateIdentityResponseBody)
            }
        }

        when: "User passes request through repose"
        def mc = deproxy.makeRequest([
                url: reposeEndpoint + requestURI,
                method: requestMethod,
                requestBody: requestBody,
                headers: headers
        ])

        then: "Pass all the things"
        mc.orphanedHandlings.size == orphanedHandlings
        mc.receivedResponse.code == responseCode
        mc.receivedResponse.message == responseMessage

        if(validateResponseCode == 200){
            //validate x-auth-token is impersonated
            assert mc.handlings[0].request.headers.contains("x-auth-token")
            assert mc.handlings[0].request.headers.getFirstValue("x-auth-token") == impersonatedToken
        }

        where:

        reqTenant | validateResponseCode | validateIdentityResponseBody                               | requestMethod | requestURI | requestBody | headers                 | responseCode | responseMessage                                              | orphanedHandlings
        300       | 500                  | ""                                                         | "GET"         | "/"        | ""          | ['x-auth-token': '123'] | "502"        | "Identity Service not available to get impersonation token"  | 5 //first time needs to admin auth
        301       | 500                  | null                                                       | "GET"         | "/"        | ""          | ['x-auth-token': '234'] | "502"        | "Identity Service not available to get impersonation token"  | 3
        302       | 404                  | fakeIdentityService.impersonateTokenJsonFailedAuthTemplate | "GET"         | "/"        | ""          | ['x-auth-token': '345'] | "401"        | "Unable to find username in Identity."                       | 3
        303       | 401                  | fakeIdentityService.impersonateTokenJsonFailedAuthTemplate | "GET"         | "/"        | ""          | ['x-auth-token': '456'] | "500"        | "Unable to authenticate your admin user"                     | 4
        304       | 200                  | ""                                                         | "GET"         | "/"        | ""          | ['x-auth-token': '567'] | "200"        | "OK"                                                         | 3
        305       | 200                  | ""                                                         | "GET"         | "/"        | ""          | ['x-auth-token': '678'] | "200"        | "OK"                                                         | 3
    }
}