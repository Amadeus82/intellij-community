package com.intellij.psi.formatter.xml;

import com.intellij.codeFormatting.general.FormatterUtil;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.StdLanguages;
import com.intellij.formatting.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.jsp.jspJava.JspText;
import com.intellij.psi.impl.source.tree.ElementType;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.xml.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;


public abstract class AbstractXmlBlock extends AbstractBlock {
  protected final XmlFormattingPolicy myXmlFormattingPolicy;
  public static final String JSPX_DECLARATION_TAG_NAME = "jsp:declaration";
  public static final String JSPX_SCRIPTLET_TAG_NAME = "jsp:scriptlet";

  public AbstractXmlBlock(final ASTNode node,
                          final Wrap wrap,
                          final Alignment alignment,
                          final XmlFormattingPolicy policy) {
    super(node, wrap, alignment);
    myXmlFormattingPolicy = policy;
    if (node.getTreeParent() == null) {
      myXmlFormattingPolicy.setRootBlock(node, this);
    }
  }


  protected int getWrapType(final int type) {
    if (type == CodeStyleSettings.DO_NOT_WRAP) return Wrap.NONE;
    if (type == CodeStyleSettings.WRAP_ALWAYS) return Wrap.ALWAYS;
    if (type == CodeStyleSettings.WRAP_AS_NEEDED) return Wrap.NORMAL;
    return Wrap.CHOP_DOWN_IF_LONG;
  }

  protected Alignment chooseAlignment(final ASTNode child, final Alignment attrAlignment, final Alignment textAlignment) {
    if (myNode.getElementType() == ElementType.XML_TEXT) return getAlignment();
    final IElementType elementType = child.getElementType();
    if (elementType == ElementType.XML_ATTRIBUTE && myXmlFormattingPolicy.getShouldAlignAttributes()) return attrAlignment;
    if (elementType == ElementType.XML_TEXT && myXmlFormattingPolicy.getShouldAlignText()) return textAlignment;
    return null;
  }

  private Wrap getTagEndWrapping(final XmlTag parent) {
    return Wrap.createWrap(myXmlFormattingPolicy.getWrappingTypeForTagEnd(parent), true);
  }

  protected Wrap chooseWrap(final ASTNode child, final Wrap tagBeginWrap, final Wrap attrWrap, final Wrap textWrap) {
    if (myNode.getElementType() == ElementType.XML_TEXT) return textWrap;
    final IElementType elementType = child.getElementType();
    if (elementType == ElementType.XML_ATTRIBUTE) return attrWrap;
    if (elementType == ElementType.XML_START_TAG_START) return tagBeginWrap;
    if (elementType == ElementType.XML_END_TAG_START) {
      final PsiElement parent = SourceTreeToPsiMap.treeElementToPsi(child.getTreeParent());
      if ((parent instanceof XmlTag) && canWrapTagEnd((XmlTag)parent)) {
        return getTagEndWrapping((XmlTag)parent);
      } else {
        return null;
      }
    }
    if (elementType == ElementType.XML_TEXT || elementType == ElementType.XML_DATA_CHARACTERS) return textWrap;
    return null;
  }

  private boolean canWrapTagEnd(final XmlTag tag) {
    return tag.getSubTags().length > 0 || tag.getName().toLowerCase().startsWith("jsp:");
  }

  protected XmlTag getTag() {
    return getTag(myNode);
  }

  protected XmlTag getTag(final ASTNode node) {
    final PsiElement element = SourceTreeToPsiMap.treeElementToPsi(node);
    if (element instanceof XmlTag) {
      return (XmlTag)element;
    } else {
      return null;
    }
  }

  protected Wrap createTagBeginWrapping(final XmlTag tag) {
    return Wrap.createWrap(myXmlFormattingPolicy.getWrappingTypeForTagBegin(tag), true);
  }

  protected @Nullable ASTNode processChild(List<Block> result,
                                           final ASTNode child,
                                           final Wrap wrap,
                                           final Alignment alignment,
                                           final Indent indent) {
    final Language myLanguage = myNode.getPsi().getLanguage();
    final Language childLanguage = child.getPsi().getLanguage();
    if (useMyFormatter(myLanguage, childLanguage)) {

      Block jspScriptletNode = buildBlockForScriptletNode(child,indent);
      if (jspScriptletNode != null) {
        result.add(jspScriptletNode);
        return child;
      }

      if (canBeAnotherTreeTagStart(child)) {
        PsiElement tag = JspTextBlock.findXmlElementAt(child, child.getStartOffset());
        if (tag instanceof XmlTag
            && containsTag(tag)
            && doesNotIntersectSubTagsWith(tag)) {
          ASTNode currentChild = createAnotherTreeTagBlock(result, child, tag, indent, wrap, alignment);

          if (currentChild == null) {
            return null;
          }

          while (currentChild != null && currentChild.getTreeParent() != myNode && currentChild.getTreeParent() != child.getTreeParent()) {
            currentChild = processAllChildrenFrom(result, currentChild, wrap, alignment, indent);
            if (currentChild != null && (currentChild.getTreeParent() == myNode || currentChild.getTreeParent() == child.getTreeParent())) {
              return currentChild;
            }
            if (currentChild != null) {
              currentChild = currentChild.getTreeParent();

            }
          }

          return currentChild;
        }
      }

      processSimpleChild(child, indent, result, wrap, alignment);
      return child;

    } else {
      return createAnotherLanguageBlockWrapper(childLanguage, child, result);
    }
  }

  private boolean doesNotIntersectSubTagsWith(final PsiElement tag) {
    final TextRange tagRange = tag.getTextRange();
    final XmlTag[] subTags = getSubTags();
    for (XmlTag subTag : subTags) {
      final TextRange subTagRange = subTag.getTextRange();
      if (subTagRange.getEndOffset() < tagRange.getStartOffset()) continue;
      if (subTagRange.getStartOffset() > tagRange.getEndOffset()) return true;

      if (tagRange.getStartOffset() > subTagRange.getStartOffset() && tagRange.getEndOffset() < subTagRange.getEndOffset()) return false;
      if (tagRange.getEndOffset() > subTagRange.getStartOffset() && tagRange.getEndOffset() < subTagRange.getEndOffset()) return false;

    }
    return true;
  }

  private XmlTag[] getSubTags() {

    if (myNode instanceof XmlTag) {
      return ((XmlTag)myNode.getPsi()).getSubTags();
    } else if (myNode.getPsi() instanceof XmlElement){
      return collectSubTags((XmlElement)myNode.getPsi());
    } else {
      return new XmlTag[0];
    }

  }

  private XmlTag[] collectSubTags(final XmlElement node) {
    final List<XmlTag> result = new ArrayList<XmlTag>();
    node.processElements(new PsiElementProcessor() {
      public boolean execute(final PsiElement element) {
        if (element instanceof XmlTag) {
          result.add((XmlTag)element);
        }
        return true;
      }
    }, node);
    return result.toArray(new XmlTag[result.size()]);
  }

  private boolean containsTag(final PsiElement tag) {
    final ASTNode closingTagStart = XmlChildRole.CLOSING_TAG_START_FINDER.findChild(myNode);
    final ASTNode startTagStart = XmlChildRole.START_TAG_END_FINDER.findChild(myNode);

    if (closingTagStart == null && startTagStart == null) {
      return tag.getTextRange().getEndOffset() <= myNode.getTextRange().getEndOffset();
    }
    else if (closingTagStart == null) {
      return false;
    }
    else {
      return tag.getTextRange().getEndOffset() <= closingTagStart.getTextRange().getEndOffset();
    }
  }

  private ASTNode processAllChildrenFrom(final List<Block> result,
                                         final @NotNull ASTNode child,
                                         final Wrap wrap,
                                         final Alignment alignment,
                                         final Indent indent) {
    ASTNode resultNode = child;
    ASTNode currentChild = child.getTreeNext();
    while (currentChild!= null && currentChild.getElementType() != ElementType.XML_END_TAG_START) {
      if (!FormatterUtil.containsWhiteSpacesOnly(currentChild)) {
        currentChild = processChild(result, currentChild, wrap, alignment, indent);
        resultNode = currentChild;
      }
      if (currentChild != null) {
        currentChild = currentChild.getTreeNext();
      }
    }
    return resultNode;
  }

  private void processSimpleChild(final ASTNode child,
                                  final Indent indent,
                                  final List<Block> result,
                                  final Wrap wrap,
                                  final Alignment alignment) {
    if (myXmlFormattingPolicy.processJsp() &&
        (child.getElementType() == ElementType.JSP_XML_TEXT
         || child.getPsi() instanceof JspText)) {
      final Pair<PsiElement, Language> root = JspTextBlock.findPsiRootAt(child);
      if (root != null) {
        createJspTextNode(result, child, indent);
        return;
      }
    }

    if (isXmlTag(child) || child.getElementType() == ElementType.XML_TAG) {
      result.add(new XmlTagBlock(child, wrap, alignment, myXmlFormattingPolicy, indent != null ? indent : Indent.getNoneIndent()));
    }
    else if (child.getElementType() == ElementType.JSP_SCRIPTLET_END) {
      result.add(new XmlBlock(child, wrap, alignment, myXmlFormattingPolicy, Indent.getNoneIndent(), null));
    }
    else {
      result.add(new XmlBlock(child, wrap, alignment, myXmlFormattingPolicy, indent, null));
    }
  }

  private ASTNode createAnotherLanguageBlockWrapper(final Language childLanguage, final ASTNode child, final List<Block> result) {
    final FormattingModelBuilder builder = childLanguage.getFormattingModelBuilder();
    LOG.assertTrue(builder != null);
    final FormattingModel childModel = builder.createModel(child.getPsi(),
                                                           myXmlFormattingPolicy.getSettings());
    result.add(new AnotherLanguageBlockWrapper(child, myXmlFormattingPolicy, childModel.getRootBlock()));
    return child;
  }

  private ASTNode createAnotherTreeTagBlock(final List<Block> result,
                                            final ASTNode child,
                                            PsiElement tag,
                                            final Indent indent,
                                            final Wrap wrap, final Alignment alignment) {
    Indent childIndent = indent;

    if (myNode.getElementType() == ElementType.HTML_DOCUMENT
        && tag.getParent() instanceof XmlTag
        && myXmlFormattingPolicy.indentChildrenOf((XmlTag)tag.getParent())) {
      childIndent = Indent.createNormalIndent();
    }
    result.add(new XmlTagBlock(tag.getNode(), null, null, myXmlFormattingPolicy, childIndent));
    ASTNode currentChild = findChildAfter(child, tag.getTextRange().getEndOffset());

    while (currentChild != null && currentChild.getTextRange().getEndOffset() > tag.getTextRange().getEndOffset()) {
      PsiElement psiElement = JspTextBlock.findXmlElementAt(currentChild, tag.getTextRange().getEndOffset());
      if (psiElement != null) {
        if (psiElement instanceof XmlTag &&
            psiElement.getTextRange().getStartOffset() >= currentChild.getTextRange().getStartOffset() &&
            containsTag(psiElement)) {
          result.add(new XmlTagBlock(psiElement.getNode(), null, null, myXmlFormattingPolicy, childIndent));
          currentChild = findChildAfter(currentChild, psiElement.getTextRange().getEndOffset());
          tag = psiElement;
        } else {
          result.add(new XmlBlock(currentChild, wrap, alignment, myXmlFormattingPolicy, indent, new TextRange(tag.getTextRange().getEndOffset(),
                                                                                                              currentChild.getTextRange().getEndOffset())));
          return currentChild;
        }
      }
    }

    return currentChild;
  }

  private boolean canBeAnotherTreeTagStart(final ASTNode child) {
    return myXmlFormattingPolicy.processJsp()
           && myNode.getPsi().getContainingFile().getLanguage() == StdLanguages.JSP
           && (isXmlTag(myNode) || myNode.getElementType() == ElementType.HTML_DOCUMENT || myNode.getPsi() instanceof PsiFile) &&
           (child.getElementType() == ElementType.XML_DATA_CHARACTERS || child.getElementType() == ElementType.JSP_XML_TEXT || child.getPsi() instanceof JspText);
  }

  protected boolean isXmlTag(final ASTNode child) {
    return child.getPsi() instanceof XmlTag;
  }

  private Block buildBlockForScriptletNode(final ASTNode child, final Indent indent) {
    if (!(child.getPsi() instanceof JspText)) return null;
    ASTNode element = child.getPsi().getContainingFile()
      .getPsiRoots()[0].getNode().findLeafElementAt(child.getTextRange().getStartOffset());
    if (element != null && (element.getElementType() == ElementType.JSP_SCRIPTLET_START
                            || element.getElementType() == ElementType.JSP_DECLARATION_START
                            || element.getElementType() == ElementType.JSP_EXPRESSION_START)) {
      final ArrayList<Block> subBlocks = new ArrayList<Block>();
      while (element != null && element.getTextRange().getEndOffset() <=child.getTextRange().getEndOffset()) {
        if (!FormatterUtil.containsWhiteSpacesOnly(element)) {
          processChild(subBlocks, element, null, null, Indent.getNoneIndent());
        }
        int nextOffset = element.getTextRange().getEndOffset();
        element = element.getTreeNext();
        if (element == null) {
          element = child.getPsi().getContainingFile()
            .getPsiRoots()[0].getNode().findLeafElementAt(nextOffset);
        }
      }
      return new SyntheticBlock(subBlocks, this, indent, myXmlFormattingPolicy, Indent.createNormalIndent());
    } else {
      return null;
    }
  }

  private boolean useMyFormatter(final Language myLanguage, final Language childLanguage) {
    return myLanguage == childLanguage
           || childLanguage == StdLanguages.JAVA
           || childLanguage == StdLanguages.HTML
           || childLanguage == StdLanguages.XML
           || childLanguage == StdLanguages.JSP
           || childLanguage == StdLanguages.JSPX
           || childLanguage.getFormattingModelBuilder() == null;
  }

  protected boolean isJspxJavaContainingNode(final ASTNode child) {
    if (child.getElementType() != ElementType.XML_TEXT) return false;
    final ASTNode treeParent = child.getTreeParent();
    if (treeParent == null) return false;
    if (treeParent.getElementType() != ElementType.XML_TAG) return false;
    final PsiElement psiElement = SourceTreeToPsiMap.treeElementToPsi(treeParent);
    final String name = ((XmlTag)psiElement).getName();
    if (!(Comparing.equal(name, JSPX_SCRIPTLET_TAG_NAME)
          || Comparing.equal(name, JSPX_DECLARATION_TAG_NAME))){
      return false;
    }
    if (child.getText().trim().length() == 0) return false;
    return JspTextBlock.findPsiRootAt(child) != null;
  }

  public ASTNode getTreeNode() {
    return myNode;
  }

  public abstract boolean insertLineBreakBeforeTag();

  public abstract boolean removeLineBreakBeforeTag();

  protected SpaceProperty createDefaultSpace(boolean forceKeepLineBreaks) {
    boolean shouldKeepLineBreaks = myXmlFormattingPolicy.getShouldKeepLineBreaks() || forceKeepLineBreaks;
    return SpaceProperty.createSpaceProperty(0, Integer.MAX_VALUE, 0, shouldKeepLineBreaks, myXmlFormattingPolicy.getKeepBlankLines());
  }

  public abstract boolean isTextElement();

  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.formatter.xml.AbstractXmlBlock");

  public static Block creareJspRoot(final PsiElement element, final CodeStyleSettings settings) {
    final PsiElement[] psiRoots = (element.getContainingFile()).getPsiRoots();
    LOG.assertTrue(psiRoots.length == 4);
    final ASTNode rootNode = SourceTreeToPsiMap.psiElementToTree(psiRoots[1]);
    return new XmlBlock(rootNode, null, null, new HtmlPolicy(settings), null, null);
  }

  public static Block creareJspxRoot(final PsiElement element, final CodeStyleSettings settings) {
    final ASTNode rootNode = SourceTreeToPsiMap.psiElementToTree(element);
    return new XmlBlock(rootNode, null, null, new HtmlPolicy(settings), null, null);
  }

  @Nullable
  protected void createJspTextNode(final List<Block> localResult, final ASTNode child, final Indent indent) {

    localResult.add(new JspTextBlock(child,
                                     myXmlFormattingPolicy,
                                     JspTextBlock.findPsiRootAt(child),
                                     indent
    ));
  }

  private ASTNode findChildAfter(@NotNull final ASTNode child, final int endOffset) {
    ASTNode result = child;
    while (result != null && result.getTextRange().getEndOffset() < endOffset) {
      result = TreeUtil.nextLeaf(result);
    }
    return result;
  }

}
