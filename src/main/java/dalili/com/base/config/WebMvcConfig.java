package dalili.com.base.config;

import dalili.com.base.interfaces.web.ApiQueryLoggingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers Spring MVC infrastructure that is not part of security filter processing.
 *
 * <p>This configuration currently attaches the API query logging interceptor to all
 * {@code /api/**} routes so controller-bound read requests are logged in one place.</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiQueryLoggingInterceptor apiQueryLoggingInterceptor;

    public WebMvcConfig(ApiQueryLoggingInterceptor apiQueryLoggingInterceptor) {
        this.apiQueryLoggingInterceptor = apiQueryLoggingInterceptor;
    }

    /**
     * Adds MVC interceptors that should run around controller invocation.
     *
     * <p>The query logging interceptor is registered broadly on {@code /api/**}.
     * The interceptor itself decides which requests are actually logged, so path
     * registration stays simple while logging rules remain centralized.</p>
     *
     * @param registry Spring MVC interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiQueryLoggingInterceptor).addPathPatterns("/api/**");
    }
}
