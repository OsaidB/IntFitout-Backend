package life.work.IntFit.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import life.work.IntFit.backend.dto.CloseoutFinalizeResponseDTO;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.repository.CloseoutDraftRepository;
import life.work.IntFit.backend.repository.CloseoutFinalRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.service.CloseoutFinalizeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CloseoutFinalizeServiceImpl implements CloseoutFinalizeService {

    private static final String UNIT_M = "m";
    private static final String UNIT_M2_A = "m²";
    private static final String UNIT_M2_B = "m2";
    private static final String UNIT_COUNT = "count";

    private static final String CAT_GYPSUM = "gypsum";
    private static final String CAT_PAINTING = "painting";

    private final CloseoutFinalRepository closeoutFinalRepository;
    private final CloseoutDraftRepository closeoutDraftRepository;
    private final MasterWorksiteRepository masterWorksiteRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public CloseoutFinalizeResponseDTO finalizeCloseout(Long masterWorksiteId, JsonNode optionalDraftOverride) {
        MasterWorksite master = masterWorksiteRepository.findById(masterWorksiteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Master worksite not found"));

        JsonNode draftNode = optionalDraftOverride;
        String snapshotJson;

        if (draftNode == null || draftNode.isNull()) {
            CloseoutDraft draft = closeoutDraftRepository.findByMasterWorksite_Id(masterWorksiteId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No closeout draft found for this master worksite"));

            snapshotJson = draft.getDraftJson();
            draftNode = readTreeSafe(snapshotJson);
        } else {
            snapshotJson = draftNode.toString();
        }

        if (draftNode == null || draftNode.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft payload is empty/invalid");
        }

        Instant now = Instant.now();
        Instant draftUpdatedAt = parseInstantSafe(draftNode.path("updatedAt").asText(null));

        CloseoutFinal finalEntity = CloseoutFinal.builder()
                .masterWorksite(master)
                .finalizedAt(now)
                .draftUpdatedAt(draftUpdatedAt)
                .draftSnapshotJson(snapshotJson)
                .gypsumTotal(money(BigDecimal.ZERO))
                .paintingTotal(money(BigDecimal.ZERO))
                .grandTotal(money(BigDecimal.ZERO))
                .build();

        List<CloseoutFinalType> typeEntities = new ArrayList<>();

        BigDecimal gypsumTotal = BigDecimal.ZERO;
        BigDecimal paintingTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        JsonNode typesNode = draftNode.path("types");
        if (typesNode.isArray()) {
            int typeIndex = 0;
            for (JsonNode t : typesNode) {
                String name = text(t, "name").trim();
                if (name.isEmpty()) continue;

                String unit = normalizeUnit(text(t, "unit"));
                String category = normalizeCategory(text(t, "category"));
                BigDecimal unitPrice = money(parseDecimal(text(t, "unitPrice"), BigDecimal.ZERO));

                CloseoutFinalType typeEntity = CloseoutFinalType.builder()
                        .closeoutFinal(finalEntity)
                        .typeIndex(typeIndex)
                        .clientTypeId(text(t, "id"))
                        .name(name)
                        .unit(unit)
                        .category(category)
                        .unitPrice(unitPrice)
                        .quantityTotal(qty(BigDecimal.ZERO))
                        .subtotal(money(BigDecimal.ZERO))
                        .build();

                List<CloseoutFinalRow> rowEntities = new ArrayList<>();
                BigDecimal typeQtyTotal = BigDecimal.ZERO;
                BigDecimal typeSubtotal = BigDecimal.ZERO;

                JsonNode rowsNode = t.path("rows");
                if (rowsNode.isArray()) {
                    int rowIndex = 0;
                    for (JsonNode r : rowsNode) {
                        BigDecimal len = parseDecimal(text(r, "length"), null);
                        BigDecimal wid = parseDecimal(text(r, "width"), null);
                        BigDecimal area = parseDecimal(text(r, "area"), null);
                        BigDecimal cnt = parseDecimal(text(r, "count"), null);

                        String mode = text(r, "mode");
                        BigDecimal quantity = computeQuantity(unit, mode, len, wid, area, cnt);
                        BigDecimal rowSubtotal = money(quantity.multiply(unitPrice));

                        CloseoutFinalRow rowEntity = CloseoutFinalRow.builder()
                                .type(typeEntity)
                                .rowIndex(rowIndex)
                                .clientRowId(text(r, "id"))
                                .room(text(r, "room"))
                                .note(text(r, "note"))
                                .mode(mode)
                                .lengthVal(len)
                                .widthVal(wid)
                                .areaVal(area)
                                .countVal(cnt)
                                .quantity(qty(quantity))
                                .unitPrice(unitPrice)
                                .subtotal(rowSubtotal)
                                .build();

                        rowEntities.add(rowEntity);

                        typeQtyTotal = typeQtyTotal.add(quantity);
                        typeSubtotal = typeSubtotal.add(rowSubtotal);

                        rowIndex++;
                    }
                }

                typeEntity.setRows(rowEntities);
                typeEntity.setQuantityTotal(qty(typeQtyTotal));
                typeEntity.setSubtotal(money(typeSubtotal));

                // accumulate category + grand totals
                if (CAT_PAINTING.equals(category)) paintingTotal = paintingTotal.add(typeSubtotal);
                else gypsumTotal = gypsumTotal.add(typeSubtotal);

                grandTotal = grandTotal.add(typeSubtotal);

                typeEntities.add(typeEntity);
                typeIndex++;
            }
        }

        finalEntity.setTypes(typeEntities);
        finalEntity.setGypsumTotal(money(gypsumTotal));
        finalEntity.setPaintingTotal(money(paintingTotal));
        finalEntity.setGrandTotal(money(grandTotal));

        CloseoutFinal saved = closeoutFinalRepository.save(finalEntity);

        return CloseoutFinalizeResponseDTO.builder()
                .closeoutFinalId(saved.getId())
                .masterWorksiteId(masterWorksiteId)
                .gypsumTotal(saved.getGypsumTotal())
                .paintingTotal(saved.getPaintingTotal())
                .grandTotal(saved.getGrandTotal())
                .finalizedAt(saved.getFinalizedAt())
                .build();
    }

    // ---------------- helpers ----------------

    private JsonNode readTreeSafe(String json) {
        try {
            if (json == null || json.isBlank()) return null;
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant parseInstantSafe(String iso) {
        try {
            if (iso == null || iso.isBlank()) return null;
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node, String key) {
        if (node == null || node.isNull()) return "";
        JsonNode v = node.get(key);
        return (v == null || v.isNull()) ? "" : v.asText("");
    }

    private String normalizeUnit(String u) {
        String x = (u == null ? "" : u.trim());
        if (x.equalsIgnoreCase(UNIT_M)) return UNIT_M;
        if (x.equalsIgnoreCase(UNIT_COUNT)) return UNIT_COUNT;
        if (x.equalsIgnoreCase(UNIT_M2_B)) return UNIT_M2_A;
        if (x.equalsIgnoreCase("m^2")) return UNIT_M2_A;
        if (x.equals(UNIT_M2_A)) return UNIT_M2_A;
        return UNIT_M2_A; // default
    }

    private String normalizeCategory(String c) {
        String x = (c == null ? "" : c.trim().toLowerCase());
        if (CAT_PAINTING.equals(x)) return CAT_PAINTING;
        return CAT_GYPSUM;
    }

    private BigDecimal parseDecimal(String s, BigDecimal def) {
        try {
            if (s == null) return def;
            String clean = s.trim();
            if (clean.isEmpty()) return def;
            clean = clean.replace(",", ""); // safety
            return new BigDecimal(clean);
        } catch (Exception e) {
            return def;
        }
    }

    private BigDecimal computeQuantity(String unit, String mode, BigDecimal len, BigDecimal wid, BigDecimal area, BigDecimal cnt) {
        // For non-count units, "count" is a multiplier (default 1)
        BigDecimal multiplier = (cnt == null ? BigDecimal.ONE : cnt);
        if (UNIT_COUNT.equals(unit)) {
            // For count unit, "count" is the actual quantity (default 0)
            return (cnt == null ? BigDecimal.ZERO : cnt);
        }

        if (UNIT_M.equals(unit)) {
            BigDecimal L = (len == null ? BigDecimal.ZERO : len);
            return L.multiply(multiplier);
        }

        // m²
        boolean isAreaMode = mode != null && mode.equalsIgnoreCase("area");
        BigDecimal base;
        if (isAreaMode) {
            base = (area == null ? BigDecimal.ZERO : area);
        } else {
            BigDecimal L = (len == null ? BigDecimal.ZERO : len);
            BigDecimal W = (wid == null ? BigDecimal.ZERO : wid);
            base = L.multiply(W);
        }
        return base.multiply(multiplier);
    }

    private BigDecimal money(BigDecimal v) {
        if (v == null) v = BigDecimal.ZERO;
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal qty(BigDecimal v) {
        if (v == null) v = BigDecimal.ZERO;
        return v.setScale(4, RoundingMode.HALF_UP);
    }
}
