package org.oppia.android.domain.classify.rules.multiplechoiceinput

import org.oppia.android.app.model.InteractionObject
import org.oppia.android.app.model.WrittenTranslationContext
import org.oppia.android.domain.classify.RuleClassifier
import org.oppia.android.domain.classify.rules.GenericRuleClassifier
import org.oppia.android.domain.classify.rules.RuleClassifierProvider
import javax.inject.Inject

/**
 * Provider for a classifier that determines whether a multiple choice answer matches a specific option per the multiple
 * choice input interaction.
 *
 * https://github.com/oppia/oppia/blob/37285a/extensions/interactions/MultipleChoiceInput/directives/multiple-choice-input-rules.service.ts#L21
 */
// TODO(#1580): Re-restrict access using Bazel visibilities
class MultipleChoiceInputEqualsRuleClassifierProvider @Inject constructor(
  private val classifierFactory: GenericRuleClassifier.Factory
) : RuleClassifierProvider, GenericRuleClassifier.SingleInputMatcher<Int> {

  override fun createRuleClassifier(): RuleClassifier {
    return classifierFactory.createSingleInputClassifier(
      InteractionObject.ObjectTypeCase.NON_NEGATIVE_INT,
      "x",
      this
    )
  }

  override fun matches(
    answer: Int,
    input: Int,
    writtenTranslationContext: WrittenTranslationContext
  ): Boolean = answer == input
}
