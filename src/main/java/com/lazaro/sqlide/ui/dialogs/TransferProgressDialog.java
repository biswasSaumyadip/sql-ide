package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.transfer.DataTransferService;
import com.lazaro.sqlide.core.transfer.TransferRequest;
import com.lazaro.sqlide.core.transfer.TransferResult;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Modal progress UI for an in-flight {@link DataTransferService} run.
 */
public final class TransferProgressDialog extends Dialog<TransferResult> {

    private final ProgressBar progressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Label statusLabel = new Label("Preparing\u2026");
    private final Label detailLabel = new Label();
    private final AtomicReference<DataTransferService> serviceRef = new AtomicReference<>();
    private final Task<TransferResult> task;

    public TransferProgressDialog(Window owner, TransferRequest request, Consumer<String> logSink) {
        Objects.requireNonNull(request, "request");
        Consumer<String> log = logSink == null ? line -> { } : logSink;

        setTitle("Transferring data");
        setHeaderText(request.sourceTable() + " \u2192 " + request.targetTable());
        setResizable(false);
        if (owner != null) {
            initOwner(owner);
        }

        progressBar.setMaxWidth(Double.MAX_VALUE);
        statusLabel.getStyleClass().add("transfer-progress-status");
        detailLabel.getStyleClass().add("import-wizard-hint");
        detailLabel.setWrapText(true);

        VBox body = new VBox(12, statusLabel, progressBar, detailLabel);
        body.setPadding(new Insets(16));
        body.setPrefWidth(460);
        VBox.setVgrow(progressBar, Priority.NEVER);
        getDialogPane().setContent(body);
        getDialogPane().getStyleClass().add("transfer-progress-dialog");
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL);

        Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        cancel.setText("Cancel");
        cancel.getStyleClass().addAll(Styles.FLAT, "import-wizard-button");

        long started = System.nanoTime();
        task = new Task<>() {
            @Override
            protected TransferResult call() throws Exception {
                DataTransferService service = new DataTransferService();
                serviceRef.set(service);
                return service.transfer(request, (transferred, total, detail) -> {
                    if (isCancelled() || service.isCancelRequested()) {
                        return;
                    }
                    long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                    double rate = elapsedMs <= 0 ? 0 : transferred * 1000.0 / elapsedMs;
                    Platform.runLater(() -> {
                        if (total > 0) {
                            progressBar.setProgress(Math.min(1.0, (double) transferred / total));
                            statusLabel.setText("Transferred %,d of %,d rows\u2026".formatted(transferred, total));
                        } else {
                            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                            statusLabel.setText("Transferred %,d rows\u2026".formatted(transferred));
                        }
                        detailLabel.setText("%s \u00B7 %.0f rows/s \u00B7 %s"
                                .formatted(detail, rate, formatElapsed(elapsedMs)));
                    });
                    log.accept(detail);
                });
            }
        };

        cancel.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            DataTransferService service = serviceRef.get();
            if (service != null) {
                service.requestCancel();
            }
            task.cancel(true);
            statusLabel.setText("Cancelling\u2026");
        });

        task.setOnSucceeded(event -> {
            setResult(task.getValue());
            close();
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            String message = error == null ? "Transfer failed" : String.valueOf(error.getMessage());
            setResult(new TransferResult(
                    request.resolveStrategy(),
                    0,
                    0,
                    (System.nanoTime() - started) / 1_000_000L,
                    false,
                    message,
                    List.of(message)));
            close();
        });
        task.setOnCancelled(event -> {
            setResult(new TransferResult(
                    request.resolveStrategy(),
                    0,
                    0,
                    (System.nanoTime() - started) / 1_000_000L,
                    true,
                    "Transfer cancelled",
                    List.of()));
            close();
        });

        setResultConverter(button -> getResult());
    }

    public Task<TransferResult> task() {
        return task;
    }

    private static String formatElapsed(long elapsedMs) {
        if (elapsedMs < 1000) {
            return elapsedMs + " ms";
        }
        return "%.1f s".formatted(elapsedMs / 1000.0);
    }
}
