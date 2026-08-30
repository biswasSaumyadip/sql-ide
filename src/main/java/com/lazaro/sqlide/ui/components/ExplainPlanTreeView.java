package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.explain.ExplainPlanNode;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Renders an {@link ExplainPlanNode} tree in the outcome pane instead of a raw
 * text dump.
 */
public final class ExplainPlanTreeView extends TreeView<ExplainPlanNode> {

    public ExplainPlanTreeView() {
        getStyleClass().add("explain-plan-tree");
        setShowRoot(true);
        setCellFactory(view -> new PlanCell());
        clear();
    }

    public void setPlan(ExplainPlanNode plan) {
        if (plan == null) {
            clear();
            return;
        }
        TreeItem<ExplainPlanNode> root = toItem(plan);
        setRoot(root);
        root.setExpanded(true);
        expandShallow(root, 2);
    }

    public void clear() {
        setRoot(null);
    }

    private static TreeItem<ExplainPlanNode> toItem(ExplainPlanNode node) {
        TreeItem<ExplainPlanNode> item = new TreeItem<>(node);
        for (ExplainPlanNode child : node.children()) {
            item.getChildren().add(toItem(child));
        }
        return item;
    }

    private static void expandShallow(TreeItem<ExplainPlanNode> item, int depth) {
        if (depth <= 0) {
            return;
        }
        item.setExpanded(true);
        for (TreeItem<ExplainPlanNode> child : item.getChildren()) {
            expandShallow(child, depth - 1);
        }
    }

    private static final class PlanCell extends TreeCell<ExplainPlanNode> {
        private final Label label = new Label();
        private final Label detail = new Label();
        private final HBox box = new HBox(8);

        PlanCell() {
            label.getStyleClass().add("explain-node-label");
            detail.getStyleClass().add("explain-node-detail");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(label, spacer, detail);
            box.setFillHeight(true);
        }

        @Override
        protected void updateItem(ExplainPlanNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            label.setText(item.label());
            detail.setText(item.detail());
            detail.setVisible(!item.detail().isBlank());
            detail.setManaged(!item.detail().isBlank());
            setGraphic(box);
            setText(null);
        }
    }
}
