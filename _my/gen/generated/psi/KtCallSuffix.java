// This is a generated file. Not intended for manual editing.
package generated.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface KtCallSuffix extends PsiElement {

  @Nullable
  KtFloatConstant getFloatConstant();

  @Nullable
  KtIntegerConstant getIntegerConstant();

  @Nullable
  KtArrayAccess getArrayAccess();

  @Nullable
  KtBinaryConstant getBinaryConstant();

  @Nullable
  KtCallSuffix getCallSuffix();

  @Nullable
  KtCallableReference getCallableReference();

  @Nullable
  KtElvisAccessExpression getElvisAccessExpression();

  @Nullable
  KtFunctionLiteral getFunctionLiteral();

  @Nullable
  KtFunctionLiteralExpression getFunctionLiteralExpression();

  @Nullable
  KtIfExpression getIfExpression();

  @Nullable
  KtJumpBreak getJumpBreak();

  @Nullable
  KtJumpContinue getJumpContinue();

  @Nullable
  KtJumpReturn getJumpReturn();

  @Nullable
  KtJumpThrow getJumpThrow();

  @NotNull
  List<KtLabel> getLabelList();

  @Nullable
  KtLoop getLoop();

  @Nullable
  KtObjectLiteral getObjectLiteral();

  @Nullable
  KtParenthesizedExpression getParenthesizedExpression();

  @Nullable
  KtReferenceExpression getReferenceExpression();

  @Nullable
  KtSafeAccessExpression getSafeAccessExpression();

  @Nullable
  KtStringTemplate getStringTemplate();

  @Nullable
  KtThisExpression getThisExpression();

  @Nullable
  KtTryBlock getTryBlock();

  @Nullable
  KtTypeArguments getTypeArguments();

  @Nullable
  KtValueArguments getValueArguments();

  @Nullable
  KtWhen getWhen();

}
