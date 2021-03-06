// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil;
import com.jetbrains.python.psi.*;

/**
 * Highlights incorrect return statements: 'return' and 'yield' outside functions
 */
public class ReturnAnnotator extends PyAnnotator {
  @Override
  public void visitPyReturnStatement(final PyReturnStatement node) {
    final PyFunction function = PsiTreeUtil.getParentOfType(node, PyFunction.class, false, PyClass.class);
    if (function == null) {
      getHolder().newAnnotation(HighlightSeverity.ERROR, "'return' outside of function").create();
    }
    if (function != null && node.getExpression() != null && function.isGenerator() && (function.isAsync() && function.isAsyncAllowed())) {
      getHolder().newAnnotation(HighlightSeverity.ERROR, "non-empty 'return' inside asynchronous generator").create();
    }
  }

  @Override
  public void visitPyYieldExpression(final PyYieldExpression node) {
    final ScopeOwner owner = ScopeUtil.getScopeOwner(node);
    if (!(owner instanceof PyFunction || owner instanceof PyLambdaExpression)) {
      getHolder().newAnnotation(HighlightSeverity.ERROR, "'yield' outside of function").create();
    }

    if (node.isDelegating() && owner instanceof PyFunction) {
      final PyFunction function = (PyFunction)owner;

      if (function.isAsync() && function.isAsyncAllowed()) {
        getHolder().newAnnotation(HighlightSeverity.ERROR, "Python does not support 'yield from' inside async functions").create();
      }
    }
  }
}
