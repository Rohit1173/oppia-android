package org.oppia.android.app.home.recentlyplayed

import android.view.View
import androidx.lifecycle.ViewModel
import org.oppia.android.app.model.PromotedStory

// TODO(#297): Add download status information to promoted-story-card.

/** [ViewModel] for displaying a promoted story. */
class PromotedStoryViewModel(
  val ongoingStory: PromotedStory,
  val entityType: String,
  private val ongoingStoryClickListener: OngoingStoryClickListener
) : RecentlyPlayedItemViewModel() {
  fun clickOnOngoingStoryTile(@Suppress("UNUSED_PARAMETER") v: View) {
    ongoingStoryClickListener.onOngoingStoryClicked(ongoingStory)
  }
}
