package org.oppia.android.app.application

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import org.oppia.android.app.activity.ActivityComponentImpl
import javax.inject.Singleton

/** Provides core infrastructure needed to support all other dependencies in the app. */
@Module(subcomponents = [ActivityComponentImpl::class])
class ApplicationModule {
  @Provides
  @Singleton
  @ApplicationContext
  fun provideApplicationContext(application: Application): Context {
    return application
  }

  // TODO(#59): Remove this provider once all modules have access to the @ApplicationContext qualifier.
  @Provides
  @Singleton
  fun provideContext(@ApplicationContext context: Context): Context {
    return context
  }
}
